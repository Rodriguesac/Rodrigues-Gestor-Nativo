package br.com.rac.print;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAC Print como serviço nativo do Android.
 * O PDF chega pelo spooler do Android e segue à impressora USB via receptor Windows.
 * NÃO contorna a tela de confirmação de impressão de outros aplicativos.
 */
public final class RacPrintService extends PrintService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    private SharedPreferences preferences() {
        return getSharedPreferences("rac_print_settings", MODE_PRIVATE);
    }

    @Override protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new Discovery();
    }

    private final class Discovery extends PrinterDiscoverySession {
        private boolean destroyed = false;

        @Override public void onStartPrinterDiscovery(List<PrinterId> priorityList) { refresh(); }
        @Override public void onStopPrinterDiscovery() { }
        @Override public void onValidatePrinters(List<PrinterId> printerIds) { refresh(); }
        @Override public void onStartPrinterStateTracking(PrinterId printerId) { refresh(); }
        @Override public void onStopPrinterStateTracking(PrinterId printerId) { }
        @Override public void onDestroy() { destroyed = true; }

        private void refresh() {
            String host = preferences().getString("ip", "");
            String key = preferences().getString("token", "");
            if (host.isEmpty() || key.length() < 16) return;
            worker.execute(() -> {
                boolean reachable = false;
                try {
                    String base = RacPrintBridgeClient.address(host, key);
                    JSONObject result = RacPrintBridgeClient.request(base + "/status", key, null, 1);
                    reachable = result.optBoolean("ok", false);
                } catch (Exception ignored) {
                    // Exibir a impressora cadastrada como indisponível em vez de ocultá-la.
                }
                final boolean available = reachable;
                main.post(() -> {
                    if (destroyed || isDestroyed()) return;
                    PrinterId id = generatePrinterId("impressora-do-pc");
                    PrintAttributes.MediaSize roll = new PrintAttributes.MediaSize(
                            "rac_80mm", "Bobina 80 mm", 3150, 11000);
                    PrinterCapabilitiesInfo capabilities = new PrinterCapabilitiesInfo.Builder(id)
                            .addMediaSize(roll, true)
                            .addResolution(new PrintAttributes.Resolution(
                                    "thermal_203", "Térmica 203 DPI", 203, 203), true)
                            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME,
                                    PrintAttributes.COLOR_MODE_MONOCHROME)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build();
                    PrinterInfo printer = new PrinterInfo.Builder(id,
                            "RAC Print — impressora do PC",
                            available ? PrinterInfo.STATUS_IDLE : PrinterInfo.STATUS_UNAVAILABLE)
                            .setCapabilities(capabilities)
                            .build();
                    addPrinters(Collections.singletonList(printer));
                });
            });
        }
    }

    @Override protected void onPrintJobQueued(PrintJob job) {
        if (!job.isQueued() || !job.start()) return;
        final String id = job.getId().toString();
        final int copies = job.getInfo().getCopies();
        final ParcelFileDescriptor descriptor = job.getDocument().getData();
        final String host = preferences().getString("ip", "");
        final String key = preferences().getString("token", "");
        if (descriptor == null) {
            job.fail("O Android não forneceu o documento PDF.");
            return;
        }
        worker.execute(() -> {
            try (ParcelFileDescriptor input = descriptor;
                 FileInputStream stream = new FileInputStream(input.getFileDescriptor())) {
                if (copies < 1 || copies > 3)
                    throw new Exception("Selecione de uma a três cópias.");
                byte[] pdf = RacPrintBridgeClient.readLimited(stream, RacPrintBridgeClient.MAX_BYTES);
                if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' ||
                        pdf[2] != 'D' || pdf[3] != 'F' || pdf[4] != '-')
                    throw new Exception("Documento não é PDF válido.");
                if (cancelled.contains(id)) return;
                String base = RacPrintBridgeClient.address(host, key);
                JSONObject accepted = RacPrintBridgeClient.request(base + "/print", key, pdf, copies);
                String jobId = accepted.getString("id");
                boolean spooled = false;
                for (int attempt = 0; attempt < 40; attempt++) {
                    if (cancelled.contains(id)) return;
                    JSONObject status = RacPrintBridgeClient.request(
                            base + "/jobs/" + jobId, key, null, 1);
                    String state = status.optString("status", "");
                    if ("spooled".equals(state)) { spooled = true; break; }
                    if ("error".equals(state))
                        throw new Exception(status.optString("error", "Erro no Windows."));
                    Thread.sleep(1000);
                }
                if (!spooled)
                    throw new Exception("Trabalho enviado; sem confirmação da fila do Windows. Verifique antes de repetir.");
                main.post(() -> {
                    if (job.isStarted() && !cancelled.contains(id)) job.complete();
                    cancelled.remove(id);
                });
            } catch (Exception ex) {
                final String message = ex.getMessage() == null ? "Erro ao imprimir" : ex.getMessage();
                main.post(() -> {
                    if (job.isStarted() && !cancelled.contains(id))
                        job.fail(message.length() > 180 ? message.substring(0, 180) : message);
                    cancelled.remove(id);
                });
            }
        });
    }

    @Override protected void onRequestCancelPrintJob(PrintJob job) {
        String id = job.getId().toString();
        cancelled.add(id);
        if (job.isQueued() || job.isStarted()) job.cancel();
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
