package br.com.rac.print;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 110;
    private static final int MAX_BYTES = 15 * 1024 * 1024;
    private EditText ip, token, receipt;
    private TextView fileName, connectionStatus, jobStatus;
    private Spinner copies, paper;
    private Uri selectedFile;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        ip = findViewById(R.id.ip);
        token = findViewById(R.id.token);
        receipt = findViewById(R.id.receipt);
        fileName = findViewById(R.id.fileName);
        connectionStatus = findViewById(R.id.connectionStatus);
        jobStatus = findViewById(R.id.jobStatus);
        copies = findViewById(R.id.copies);
        copies.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1", "2", "3"}));
        android.content.SharedPreferences settings = getSharedPreferences("rac_print_settings", MODE_PRIVATE);
        paper = findViewById(R.id.paper);
        paper.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"58 mm — bobina pequena", "80 mm — bobina grande"}));
        paper.setSelection(settings.getInt("paper_mm",80)==58?0:1);
        paper.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int index, long id) {
                settings.edit().putInt("paper_mm", index==0?58:80).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        findViewById(R.id.openEditor).setOnClickListener(v ->
                startActivity(new Intent(this, ProEditorActivity.class)));
        // Migração da v0.1.1 para que o serviço Android encontre a conexão existente.
        ip.setText(settings.getString("ip", getPreferences(MODE_PRIVATE).getString("ip", "")));
        token.setText(settings.getString("token", getPreferences(MODE_PRIVATE).getString("token", "")));
        findViewById(R.id.selectFile).setOnClickListener(v -> chooseFile());
        findViewById(R.id.connect).setOnClickListener(v -> runCheck());
        findViewById(R.id.openPrintSettings).setOnClickListener(v -> {
            saveConnection();
            startActivity(new Intent("android.settings.ACTION_PRINT_SETTINGS"));
        });
        findViewById(R.id.print).setOnClickListener(v -> print(false));
        findViewById(R.id.test).setOnClickListener(v -> print(true));
        acceptIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/pdf", "image/png", "image/jpeg", "image/webp", "text/plain"});
        startActivityForResult(intent, PICK_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && resultCode == RESULT_OK && data != null) {
            setDocument(data.getData());
        }
    }

    private void acceptIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) setDocument(uri);
            else {
                CharSequence txt = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (txt != null) { selectedFile = null; fileName.setText("Texto compartilhado"); receipt.setText(txt); }
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            setDocument(intent.getData());
        }
    }

    private void setDocument(Uri uri) {
        if (uri == null) return;
        selectedFile = uri;
        fileName.setText(displayName(uri));
        jobStatus.setText("Documento pronto para impressão");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "Documento recebido";
    }

    private void saveConnection() {
        getSharedPreferences("rac_print_settings", MODE_PRIVATE).edit()
                .putString("ip", ip.getText().toString().trim())
                .putString("token", token.getText().toString().trim()).apply();
    }

    private String serverUrl() {
        String host = ip.getText().toString().trim();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) throw new IllegalArgumentException("Use o IP local informado pelo receptor, sem https.");
        if (host.contains("/")) host = host.split("/", 2)[0];
        String[] ipPort = host.split(":", -1);
        if (ipPort.length > 2) throw new IllegalArgumentException("Endereço inválido.");
        String[] octets = ipPort[0].split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Digite o IP local do computador.");
        int[] x = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("IP inválido.");
            x[i] = Integer.parseInt(octets[i]);
            if (x[i] > 255) throw new IllegalArgumentException("IP inválido.");
        }
        boolean privateIP = x[0] == 10 || (x[0] == 172 && x[1] >= 16 && x[1] <= 31)
                || (x[0] == 192 && x[1] == 168) || (x[0] == 169 && x[1] == 254);
        if (!privateIP) throw new IllegalArgumentException("Somente IP de rede local. Não envie documentos pela internet.");
        int port = 8765;
        if (ipPort.length == 2) {
            try { port = Integer.parseInt(ipPort[1]); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Porta inválida."); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Porta inválida.");
        }
        if (token.getText().toString().trim().length() < 16) throw new IllegalArgumentException("Digite a chave do receptor do PC.");
        return "http://" + ipPort[0] + ":" + port;
    }

    private void runCheck() {
        saveConnection();
        String base, secret;
        try { base = serverUrl(); secret = token.getText().toString().trim(); }
        catch (Exception e) { connectionStatus.setText(e.getMessage()); return; }
        connectionStatus.setText("Conectando...");
        worker.execute(() -> {
            try {
                JSONObject json = request(base + "/status", "GET", secret, null, null, 1);
                String printer = json.optString("printer", "Impressora do Windows");
                show(connectionStatus, "Conectado ✓  " + printer);
            } catch (Exception e) { show(connectionStatus, "Falha: " + e.getMessage()); }
        });
    }

    private void print(boolean test) {
        saveConnection();
        final String base, secret;
        try { base = serverUrl(); secret = token.getText().toString().trim(); }
        catch (Exception e) { jobStatus.setText(e.getMessage()); return; }
        final Uri file = test ? null : selectedFile;
        final String typed = test ? "RAC PRINT - TESTE\nRodrigues Acai e Cia\n------------------------------\nImpressao de teste OK!\n------------------------------"
                : receipt.getText().toString();
        if (file == null && typed.trim().isEmpty()) {
            jobStatus.setText("Escolha um documento ou escreva o cupom.");
            return;
        }
        final int count = copies.getSelectedItemPosition() + 1;
        jobStatus.setText("Preparando impressão...");
        worker.execute(() -> {
            try {
                byte[] pdf = file != null ? toPdf(file) : receiptToPdf(typed);
                if (pdf.length > MAX_BYTES) throw new Exception("Arquivo muito grande (máximo 15 MB).");
                show(jobStatus, "Enviando PDF original ao computador...");
                JSONObject result = request(base + "/print", "POST", secret, pdf, "application/pdf", count);
                String id = result.getString("id");
                show(jobStatus, "Documento recebido; aguardando spooler...");
                for (int i = 0; i < 16; i++) {
                    Thread.sleep(700);
                    JSONObject status = request(base + "/jobs/" + id, "GET", secret, null, null, 1);
                    String state = status.optString("status", "");
                    if ("spooled".equals(state)) {
                        show(jobStatus, "Enviado ao spooler do Windows ✓ (confira a impressão)");
                        return;
                    }
                    if ("error".equals(state)) {
                        show(jobStatus, "Erro no PC: " + status.optString("error", "desconhecido"));
                        return;
                    }
                }
                show(jobStatus, "Recebido pelo PC. Verifique a fila da impressora.");
            } catch (Exception e) {
                show(jobStatus, "Não impresso: " + e.getMessage());
            }
        });
    }

    private JSONObject request(String url, String method, String secret, byte[] body, String mime, int count) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(18000);
            conn.setRequestProperty("X-RAC-Token", secret);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", mime);
                conn.setRequestProperty("X-Copies", Integer.toString(count));
                conn.setRequestProperty("X-File-Name", "documento.pdf");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
            }
            int code = conn.getResponseCode();
            InputStream response = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (response == null) throw new Exception("Sem resposta HTTP " + code);
            byte[] buf = readLimited(response, 64 * 1024);
            JSONObject obj = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            if (code >= 400) throw new Exception(obj.optString("error", "HTTP " + code));
            return obj;
        } finally { conn.disconnect(); }
    }

    private byte[] toPdf(Uri uri) throws Exception {
        String type = getContentResolver().getType(uri);
        String name = displayName(uri).toLowerCase(Locale.ROOT);
        byte[] data;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new Exception("Não foi possível abrir o arquivo.");
            data = readLimited(stream, MAX_BYTES);
        }
        if (data.length >= 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') return data;
        if (type != null && type.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp")) {
            return imageToPdf(data);
        }
        if ("text/plain".equals(type) || name.endsWith(".txt")) {
            return receiptToPdf(new String(data, StandardCharsets.UTF_8));
        }
        throw new Exception("Formato não suportado. Use PDF, JPG, PNG, WEBP ou TXT.");
    }

    private byte[] readLimited(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] part = new byte[8192];
        int length, total = 0;
        while ((length = stream.read(part)) != -1) {
            total += length;
            if (total > limit) throw new Exception("Arquivo maior que o limite permitido.");
            buf.write(part, 0, length);
        }
        return buf.toByteArray();
    }

    private byte[] imageToPdf(byte[] data) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (opts.outWidth < 1 || opts.outHeight < 1) throw new Exception("Imagem inválida.");
        opts.inSampleSize = 1;
        while (opts.outWidth / opts.inSampleSize > 3000 || opts.outHeight / opts.inSampleSize > 6000) opts.inSampleSize *= 2;
        opts.inJustDecodeBounds = false;
        Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (image == null) throw new Exception("Falha ao abrir imagem.");
        PdfDocument doc = new PdfDocument();
        try {
            int pw = 576;
            // Receptor Windows 0.2.1 continua com pagina na largura de 80mm.
            // Conteudo menor ocupa somente a area fisica de 58mm (sem extrapolar o papel).
            int usable = getSharedPreferences("rac_print_settings", MODE_PRIVATE)
                    .getInt("paper_mm", 80)==58 ? 418 : pw;
            float scaledHeight = ((float) image.getHeight()) * (usable - 32) / image.getWidth();
            int ph = Math.max(160, Math.min(6500, (int) Math.ceil(scaledHeight + 32)));
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 1).create());
            page.getCanvas().drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            page.getCanvas().drawBitmap(image, null, new android.graphics.RectF(16, 16, usable - 16, Math.min(ph - 16, 16 + scaledHeight)), p);
            doc.finishPage(page);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            doc.writeTo(stream);
            return stream.toByteArray();
        } finally { doc.close(); image.recycle(); }
    }

    private byte[] receiptToPdf(String text) throws Exception {
        PdfDocument pdf = new PdfDocument();
        try {
            final int width = 576, pageHeight = 1600, margin = 27, lineHeight = 34;
            final int usable = getSharedPreferences("rac_print_settings", MODE_PRIVATE)
                    .getInt("paper_mm",80)==58?418:width;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
            paint.setTextSize(25);
            List<String> lines = new ArrayList<>();
            for (String src : text.replace("\r", "").split("\n", -1)) {
                if (src.isEmpty()) { lines.add(""); continue; }
                while (src.length() > 0) {
                    int n = paint.breakText(src, true, usable - 2 * margin, null);
                    if (n <= 0) n = 1;
                    lines.add(src.substring(0, n));
                    src = src.substring(n);
                }
            }
            int perPage = (pageHeight - 2 * margin) / lineHeight;
            int pageCount = Math.max(1, (lines.size() + perPage - 1) / perPage);
            if (pageCount > 40) throw new Exception("Cupom longo demais (limite de 40 páginas).");
            for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
                int first = pageNumber * perPage;
                int last = Math.min(lines.size(), first + perPage);
                int usedHeight = Math.max(140, Math.min(pageHeight, margin * 2 + (last - first + 1) * lineHeight));
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(width, usedHeight, pageNumber + 1).create());
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                for (int i = first; i < last; i++) {
                    canvas.drawText(lines.get(i), margin, margin + (i - first + 1) * lineHeight, paint);
                }
                pdf.finishPage(page);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.writeTo(out);
            return out.toByteArray();
        } finally { pdf.close(); }
    }

    private void show(TextView view, String message) { runOnUiThread(() -> view.setText(message)); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
