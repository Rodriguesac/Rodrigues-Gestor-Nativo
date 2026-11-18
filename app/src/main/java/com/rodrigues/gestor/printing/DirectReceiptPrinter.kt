package com.rodrigues.gestor.printing

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.rodrigues.gestor.MainActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Impressão direta de comandas: HTML -> PDF de bobina via WebView -> RAC Print no Windows.
 * Não usa PrintManager.print(), que obrigatoriamente mostra o diálogo do Android.
 * Aceita apenas IPv4 privado e chave da instalação local; o PDF nunca vai a um servidor externo.
 */
object DirectReceiptPrinter {
    private const val PREFS = "gestor_direct_printer"
    private const val PORT = 8765
    private val worker = Executors.newSingleThreadExecutor()

    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun address(context: Context): String = preferences(context).getString("host", "") ?: ""
    fun mode(context: Context): String = preferences(context).getString("mode", "manual") ?: "manual"
    fun setMode(context: Context, value: String) {
        if (value in setOf("manual", "arrival", "accept")) {
            preferences(context).edit().putString("mode", value).apply()
        }
    }

    private fun isPrivateIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val v = parts.map { it.toIntOrNull() ?: return false }
        if (v.any { it !in 0..255 }) return false
        return v[0] == 10 ||
            (v[0] == 172 && v[1] in 16..31) ||
            (v[0] == 192 && v[1] == 168)
    }

    fun configured(context: Context): Boolean {
        val host = address(context)
        val token = preferences(context).getString("token", "").orEmpty()
        return isPrivateIp(host) && token.matches(Regex("[0-9a-fA-F]{32,128}"))
    }

    fun save(context: Context, rawHost: String, rawToken: String): Boolean {
        val host = rawHost.trim().removePrefix("http://").removeSuffix(":$PORT")
        val token = rawToken.trim()
        if (!isPrivateIp(host) || !token.matches(Regex("[0-9a-fA-F]{32,128}"))) return false
        preferences(context).edit().putString("host", host).putString("token", token).apply()
        return true
    }

    private fun report(activity: Activity, orderId: String, ok: Boolean, message: String) {
        activity.runOnUiThread {
            (activity as? MainActivity)?.onDirectPrintResult(
                JSONObject().put("id", orderId).put("ok", ok).put("message", message)
            )
        }
    }

    /**
     * Gera o PDF por uma página nativa e imprime pela LAN.
     * O driver da impressora recebe o PDF completo e decide quando cortar a bobina.
     * Não requer PrintManager, serviço de impressão Android nem confirmação em tela.
     */
    fun print(activity: Activity, html: String, orderId: String) {
        if (!configured(activity)) {
            report(activity, orderId, false, "Configure o IP e a chave do RAC Print em Mais > Configurações.")
            return
        }
        if (html.isBlank() || html.length > 200_000) {
            report(activity, orderId, false, "Comanda vazia ou grande demais.")
            return
        }
        val host = address(activity)
        val token = preferences(activity).getString("token", "").orEmpty()
        worker.execute {
            val file = try {
                File.createTempFile("rac-gestor-", ".pdf", activity.cacheDir)
            } catch (e: Exception) {
                report(activity, orderId, false, e.message ?: "Falha ao preparar arquivo de impressão.")
                return@execute
            }
            try {
                createReceiptPdf(html, file)
                val data = file.readBytes()
                if (data.size < 5 || data.size > 15 * 1024 * 1024 ||
                    !data.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                    throw IllegalStateException("Falha na geração do PDF da comanda.")
                }
                sendToWindows(host, token, data)
                report(activity, orderId, true, "Comanda enviada à fila da Bematech.")
            } catch (e: Exception) {
                report(activity, orderId, false, e.message ?: "Falha na impressão direta.")
            } finally {
                file.delete()
            }
        }
    }

    private fun createReceiptPdf(html: String, output: File) {
        // O cupom é HTML criado pelo Gestor; somente o corpo vira texto.
        // A folha tem largura exata de 80 mm (227 pontos PDF), sem imagem intermediária.
        val body = html.replace(Regex("(?is)<head\\b[^>]*>.*?</head>"), "")
        @Suppress("DEPRECATION")
        val parsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT)
        } else {
            Html.fromHtml(body)
        }
        // Manter linhas e negritos do HTML evita cortar nomes longos e complementos.
        val clean = parsed.trim()
        if (clean.isEmpty()) throw IllegalArgumentException("Comanda sem dados para imprimir.")
        val paperWidth = 227 // pontos (80 mm)
        val margin = 8
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = 13.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            isFakeBoldText = true
        }
        val layout = StaticLayout.Builder.obtain(clean, 0, clean.length, paint, paperWidth - margin * 2)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(3f, 1.0f)
            .build()
        val paperHeight = (layout.height + margin * 2).coerceAtLeast(90)
        if (paperHeight > 16000) throw IllegalArgumentException("Comanda excede o limite de tamanho.")
        val document = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(paperWidth, paperHeight, 1).create()
            val page = document.startPage(info)
            page.canvas.save()
            page.canvas.translate(margin.toFloat(), margin.toFloat())
            layout.draw(page.canvas)
            page.canvas.restore()
            document.finishPage(page)
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun connection(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("X-RAC-Token", token)
            useCaches = false
        }

    private fun sendToWindows(host: String, token: String, pdf: ByteArray) {
        val base = "http://$host:$PORT"
        val request = connection("$base/print", token)
        try {
            request.requestMethod = "POST"
            request.doOutput = true
            request.setRequestProperty("Content-Type", "application/pdf")
            request.setRequestProperty("X-Copies", "1")
            request.outputStream.use { it.write(pdf) }
            val result = request.responseCode
            val body = if (result in 200..299) request.inputStream.bufferedReader().use { it.readText() }
                       else request.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (result != 202) throw IllegalStateException("RAC Print recusou a comanda: HTTP $result. $body")
            val jobId = JSONObject(body).getString("id")
            if (!jobId.matches(Regex("[a-fA-F0-9-]{36}"))) throw IllegalStateException("ID de impressão inválido.")
            repeat(35) {
                Thread.sleep(1000)
                val poll = connection("$base/jobs/$jobId", token)
                try {
                    if (poll.responseCode != 200) throw IllegalStateException("Não foi possível confirmar a fila do PC.")
                    val status = JSONObject(poll.inputStream.bufferedReader().use { it.readText() })
                    when (status.optString("status")) {
                        "spooled" -> return
                        "error" -> throw IllegalStateException(status.optString("error", "Erro de impressão no Windows."))
                    }
                } finally { poll.disconnect() }
            }
            throw IllegalStateException("Pedido enviado, mas o PC ainda não confirmou a impressão. Confira antes de repetir.")
        } finally { request.disconnect() }
    }
}
