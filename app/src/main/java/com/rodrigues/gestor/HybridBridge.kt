package com.rodrigues.gestor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.print.PrintAttributes
import android.print.PrintManager
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.notifications.OrderRingService

class HybridBridge(
    private val activity: Activity,
) {
    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun getPin(): String = GestorCredentials.pin

    @JavascriptInterface
    fun vibrate(durationMs: Int) {
        val duration = durationMs.coerceIn(20, 1500).toLong()
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    @JavascriptInterface
    fun ring(orderId: String, number: String, client: String) {
        activity.runOnUiThread {
            OrderRingService.start(activity, orderId, number, client)
        }
    }

    @JavascriptInterface
    fun stopRing() {
        activity.runOnUiThread { OrderRingService.stop(activity) }
    }

    @JavascriptInterface
    fun keepAwake(enabled: Boolean) {
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        activity.runOnUiThread {
            val intent = Intent().apply {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            activity.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun printHtml(html: String) {
        if (html.isBlank()) return
        activity.runOnUiThread {
            val webView = WebView(activity)
            var started = false
            webView.settings.javaScriptEnabled = false
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (started) return
                    started = true
                    val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    manager.print(
                        "Rodrigues-Pedido",
                        view.createPrintDocumentAdapter("Rodrigues-Pedido"),
                        PrintAttributes.Builder()
                            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                            .build(),
                    )
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }
}
