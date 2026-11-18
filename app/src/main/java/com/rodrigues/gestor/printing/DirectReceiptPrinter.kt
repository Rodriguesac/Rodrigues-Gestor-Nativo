package com.rodrigues.gestor.printing

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rodrigues.gestor.MainActivity
import org.json.JSONObject
import java.io.File
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
        activity.runOnUiThread {
            val file = File.createTempFile("rac-gestor-", ".pdf", activity.cacheDir)
            val view = WebView(activity)
            view.settings.javaScriptEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            var launched = false
            fun fail(reason: String) {
                view.destroy()
                file.delete()
                report(activity, orderId, false, reason)
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(w: WebView, url: String?) {
                    if (launched) return
                    launched = true
                    w.evaluateJavascript(
                        "(function(){return Math.ceil(Math.max(document.body.scrollHeight,document.documentElement.scrollHeight));})()"
                    ) { answer ->
                        val cssHeight = answer.trim('"').toDoubleOrNull()?.coerceIn(120.0, 2500.0) ?: 650.0
                        // 96 CSS pixels per inch. A pequena margem adicional evita cortar a última linha.
                        val heightMils = ((cssHeight + 24.0) * 1000.0 / 96.0).toInt().coerceIn(2000, 29000)
                        val adapter = w.createPrintDocumentAdapter("Comanda-$orderId")
                        val attrs = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize("RAC_ROLL_80", "Bobina 80 mm", 3150, heightMils))
                            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build()
                        try {
                            adapter.onStart()
                            adapter.onLayout(null, attrs, CancellationSignal(),
                                object : PrintDocumentAdapter.LayoutResultCallback() {
                                    override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                                        var descriptor: ParcelFileDescriptor? = null
                                        try {
                                            descriptor = ParcelFileDescriptor.open(
                                                file,
                                                ParcelFileDescriptor.MODE_CREATE or
                                                    ParcelFileDescriptor.MODE_TRUNCATE or
                                                    ParcelFileDescriptor.MODE_READ_WRITE
                                            )
                                            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), descriptor,
                                                CancellationSignal(),
                                                object : PrintDocumentAdapter.WriteResultCallback() {
                                                    override fun onWriteFinished(pages: Array<out PageRange>?) {
                                                        try {
                                                            descriptor?.close()
                                                            adapter.onFinish()
                                                            w.destroy()
                                                            worker.execute {
                                                                try {
                                                                    val data = file.readBytes()
                                                                    if (data.size < 5 || data.size > 15 * 1024 * 1024 ||
                                                                        !data.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                                                                        throw IllegalStateException("Falha na geração do PDF da comanda.")
                                                                    }
                                                                    sendToWindows(host, token, data)
                                                                    report(activity, orderId, true, "Comanda enviada à fila da Bematech.")
                                                                } catch (e: Exception) {
                                                                    report(activity, orderId, false, e.message ?: "Falha no RAC Print.")
                                                                } finally { file.delete() }
                                                            }
                                                        } catch (e: Exception) { fail(e.message ?: "Erro ao imprimir.") }
                                                    }
                                                    override fun onWriteFailed(error: CharSequence?) {
                                                        descriptor?.close()
                                                        fail(error?.toString() ?: "Falha ao produzir a comanda.")
                                                    }
                                                    override fun onWriteCancelled() {
                                                        descriptor?.close()
                                                        fail("Impressão cancelada.")
                                                    }
                                                })
                                        } catch (e: Exception) {
                                            descriptor?.close()
                                            fail(e.message ?: "Falha ao gerar o PDF.")
                                        }
                                    }
                                    override fun onLayoutFailed(error: CharSequence?) {
                                        fail(error?.toString() ?: "Falha no layout da comanda.")
                                    }
                                    override fun onLayoutCancelled() {
                                        fail("Preparação da comanda cancelada.")
                                    }
                                }, null)
                        } catch (e: Exception) { fail(e.message ?: "Falha ao preparar a comanda.") }
                    }
                }
            }
            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
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
