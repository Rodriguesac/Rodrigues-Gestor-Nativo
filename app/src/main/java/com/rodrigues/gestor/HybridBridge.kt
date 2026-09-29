package com.rodrigues.gestor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.rodrigues.gestor.notifications.AlertPreferences
import com.rodrigues.gestor.notifications.OrderRingService
import com.rodrigues.gestor.voice.VoiceAssistantService
import com.rodrigues.gestor.voice.VoiceStateStore
import org.json.JSONObject

class HybridBridge(
    private val activity: Activity,
) {
    @JavascriptInterface
    fun isNative(): Boolean = true

    @JavascriptInterface
    fun getPin(): String = GestorCredentials.pin

    @JavascriptInterface
    fun savePin(pin: String) {
        GestorCredentials.save(activity, pin)
    }

    @JavascriptInterface
    fun setSession(token: String) {
        activity.getSharedPreferences("rodrigues_gestor_secure", Context.MODE_PRIVATE)
            .edit().putString("app_session_token", token).apply()
    }

    @JavascriptInterface
    fun getPreferences(): String = JSONObject().apply {
        put("enabled", AlertPreferences.enabled(activity))
        put("vibration", AlertPreferences.vibration(activity))
        put("volume", 100)
    }.toString()

    @JavascriptInterface
    fun setPreferences(json: String) {
        try {
            val value = JSONObject(json)
            if (value.has("enabled")) AlertPreferences.setEnabled(activity, value.optBoolean("enabled", true))
            if (value.has("vibration")) AlertPreferences.setVibration(activity, value.optBoolean("vibration", true))
        } catch (_: Throwable) { }
    }

    @JavascriptInterface
    fun getSoundPreset(): String = AlertPreferences.soundPreset(activity)

    @JavascriptInterface
    fun setSoundPreset(preset: String) {
        AlertPreferences.setSoundPreset(activity, preset)
    }

    @JavascriptInterface
    fun testSoundPreset(preset: String) {
        AlertPreferences.setSoundPreset(activity, preset)
        activity.runOnUiThread {
            OrderRingService.stop(activity)
            OrderRingService.start(activity, "sound-test", "TESTE", "Teste de alerta")
            Handler(Looper.getMainLooper()).postDelayed({ OrderRingService.stop(activity) }, 4_200L)
        }
    }

    @JavascriptInterface
    fun exit() {
        activity.runOnUiThread { activity.finish() }
    }

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
    fun syncVoiceOrders(json: String) {
        VoiceStateStore.syncOrders(json)
    }

    @JavascriptInterface
    fun setVoiceActiveOrder(id: String) {
        VoiceStateStore.setActiveOrder(id)
    }

    @JavascriptInterface
    fun clearVoiceActiveOrder() {
        VoiceStateStore.setActiveOrder(null)
    }

    @JavascriptInterface
    fun startVoiceAssistant() {
        activity.runOnUiThread {
            (activity as? MainActivity)?.ensureVoiceAssistant()
        }
    }

    @JavascriptInterface
    fun stopVoiceAssistant() {
        VoiceAssistantService.stop(activity)
    }

    @JavascriptInterface
    fun isVoiceAssistantRunning(): Boolean = VoiceAssistantService.isRunning

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
