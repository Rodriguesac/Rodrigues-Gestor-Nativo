package com.rodrigues.gestor.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.SupabaseOrdersApi
import com.rodrigues.gestor.notifications.AlertPreferences
import com.rodrigues.gestor.notifications.GestorConnectionService
import com.rodrigues.gestor.notifications.OrderRingService
import com.rodrigues.gestor.printing.OrderPrinter
import org.json.JSONObject

/** Bundled gestor20rac interface with a main-frame-only Android bridge. */
@SuppressLint("SetJavaScriptEnabled")
class HybridGestorView(
    private val activity: Activity,
    private val onVoice: () -> Unit,
    private val onTools: () -> Unit,
) : WebView(activity) {
    private var loaded = false
    private var pendingOrder: String? = null
    private val baseUrl = "https://gestor20rac.netlify.app/android-assets/gestor/index.html"

    init {
        val loader = WebViewAssetLoader.Builder()
            .setDomain("gestor20rac.netlify.app")
            .addPathHandler("/android-assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString += " RodriguesGestor/3.2.0"
        settings.setSupportMultipleWindows(false)
        setBackgroundColor(android.graphics.Color.WHITE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "RodriguesNative", setOf("https://gestor20rac.netlify.app")) { _, message, origin, mainFrame, _ ->
                if (mainFrame && origin.toString().trimEnd('/') == "https://gestor20rac.netlify.app" && url == baseUrl) {
                    runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()?.let(::handleMessage)
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.toString() == baseUrl) return false
                if (request.isForMainFrame) openExternal(request.url.toString())
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url != baseUrl) return
                loaded = true
                val pin = JSONObject.quote(GestorCredentials.pin)
                val id = pendingOrder?.let(JSONObject::quote) ?: "null"
                pendingOrder = null
                evaluateJavascript("window.startRodriguesNative($pin,$id)", null)
            }
        }
        loadUrl(baseUrl)
    }

    fun showOrder(id: String?) {
        if (id.isNullOrBlank()) return
        if (!loaded) { pendingOrder = id; return }
        evaluateJavascript("window.openNativeOrder(${JSONObject.quote(id)})", null)
    }

    fun refreshOrders() {
        if (loaded) evaluateJavascript("window.refreshNativeOrders?.()", null)
    }

    fun goBackOrExit(onExit: () -> Unit) {
        evaluateJavascript("window.nativeBack?.() || false") { handled -> if (handled != "true") onExit() }
    }

    private fun handleMessage(data: JSONObject) {
        when (data.optString("action")) {
            "voice" -> onVoice()
            "tools" -> onTools()
            "authenticated" -> {
                val pin = data.optString("pin")
                if (Regex("^[0-9]{6}$").matches(pin)) {
                    GestorCredentials.save(activity, pin)
                    GestorConnectionService.start(activity)
                }
            }
            "logout" -> {
                GestorCredentials.clear()
                OrderRingService.stop(activity)
                activity.stopService(Intent(activity, GestorConnectionService::class.java))
            }
            "silence" -> OrderRingService.stopFor(activity, data.optString("orderId"))
            "open_url" -> openExternal(data.optString("url"))
            "print" -> {
                val id = data.optString("orderId")
                if (id.isNotBlank()) SupabaseOrdersApi.findOrder(id, { order ->
                    OrderPrinter.printReceipt(activity, order, AlertPreferences.printCopies(activity), AlertPreferences.paperWidth(activity))
                }, { error -> Toast.makeText(activity, error.message ?: "Não foi possível imprimir", Toast.LENGTH_LONG).show() })
            }
        }
    }

    private fun openExternal(value: String) {
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("https", "http", "tel", "geo")) return
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: Throwable) { Toast.makeText(activity, "Nenhum aplicativo disponível para abrir este link", Toast.LENGTH_SHORT).show() }
    }
}
