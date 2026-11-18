package br.com.rac.print;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Cliente LAN do receptor Windows; nunca aceita destino público. */
final class RacPrintBridgeClient {
    static final int MAX_BYTES = 15 * 1024 * 1024;

    private RacPrintBridgeClient() { }

    static String address(String host, String secret) {
        if (secret == null || secret.trim().length() < 16)
            throw new IllegalArgumentException("Configure a chave de conexão no RAC Print.");
        if (host == null) throw new IllegalArgumentException("Configure o IP do PC.");
        host = host.trim();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.contains("/") || host.contains("@") || host.contains("#") || host.contains("?") ||
                host.startsWith("https://")) throw new IllegalArgumentException("IP local inválido.");
        String[] parts = host.split(":", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Endereço inválido.");
        String[] pieces = parts[0].split("\\.", -1);
        if (pieces.length != 4) throw new IllegalArgumentException("Use o IPv4 do PC informado pelo receptor.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!pieces[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("IP inválido.");
            octets[i] = Integer.parseInt(pieces[i]);
            if (octets[i] > 255) throw new IllegalArgumentException("IP inválido.");
        }
        if (!(octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)))
            throw new IllegalArgumentException("Somente rede Wi-Fi local é permitida.");
        int port = 8765;
        if (parts.length == 2) {
            try { port = Integer.parseInt(parts[1]); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Porta inválida."); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Porta inválida.");
        }
        return "http://" + parts[0] + ":" + port;
    }

    static JSONObject request(String url, String secret, byte[] body, int copies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(body == null ? "GET" : "POST");
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(18000);
            connection.setRequestProperty("X-RAC-Token", secret);
            if (body != null) {
                if (body.length == 0 || body.length > MAX_BYTES) throw new Exception("PDF acima de 15 MB.");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/pdf");
                connection.setRequestProperty("X-Copies", Integer.toString(copies));
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (response == null) throw new Exception("Sem resposta do receptor (" + status + ").");
            JSONObject json;
            try (InputStream in = response) {
                json = new JSONObject(new String(readLimited(in, 64 * 1024), StandardCharsets.UTF_8));
            }
            if (status >= 400) throw new Exception(json.optString("error", "Erro HTTP " + status));
            return json;
        } finally { connection.disconnect(); }
    }

    static byte[] readLimited(InputStream stream, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] part = new byte[8192];
        int n;
        while ((n = stream.read(part)) != -1) {
            if (output.size() + n > maxBytes) throw new Exception("Documento maior que o limite de 15 MB.");
            output.write(part, 0, n);
        }
        return output.toByteArray();
    }
}
