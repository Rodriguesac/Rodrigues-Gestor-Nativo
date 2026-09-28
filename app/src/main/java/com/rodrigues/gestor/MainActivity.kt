package com.rodrigues.gestor

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.notifications.DeviceRegistrar
import com.rodrigues.gestor.notifications.GestorConnectionService
import com.rodrigues.gestor.notifications.NotificationHelper

class MainActivity : ComponentActivity() {
    private var requestedOrderId: String? = null
    private var appStarted = false
    private var webView: WebView? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            GestorConnectionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrderId = intent?.getStringExtra(EXTRA_ORDER_ID)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view?.canGoBack() == true) view.goBack() else finish()
            }
        })

        if (GestorCredentials.load(this).length == 6) startGestor() else requestOperatorPin()
    }

    private fun requestOperatorPin() {
        val input = EditText(this).apply {
            hint = "PIN de 6 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rodrigues Gestor")
            .setMessage("Digite o PIN de operador.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty().filter(Char::isDigit)
                if (pin.length != 6) {
                    input.error = "Digite os 6 números do PIN"
                    return@setOnClickListener
                }
                GestorCredentials.save(this, pin)
                dialog.dismiss()
                startGestor()
            }
        }
        dialog.show()
    }

    private fun startGestor() {
        if (appStarted) return
        appStarted = true

        NotificationHelper.createChannels(this)
        requestNotificationsIfNeeded()
        FirebaseMessaging.getInstance().token.addOnSuccessListener(DeviceRegistrar::register)
        GestorConnectionService.start(this)

        val view = WebView(this)
        webView = view
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        view.addJavascriptInterface(HybridBridge(this), "AndroidGestor")
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
            @Suppress("DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                return url?.let { assetLoader.shouldInterceptRequest(android.net.Uri.parse(it)) }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                deliverRequestedOrder()
            }
        }
        setContentView(view)
        view.loadUrl(HYBRID_URL)
    }

    private fun deliverRequestedOrder() {
        val id = requestedOrderId?.trim().orEmpty()
        if (id.isBlank()) return
        requestedOrderId = null
        val safe = id.replace("\\", "\\\\").replace("'", "\\'")
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('native:open-order',{detail:{id:'$safe'}}));",
            null,
        )
    }

    override fun onResume() {
        super.onResume()
        if (appStarted) {
            GestorConnectionService.start(this)
            webView?.onResume()
        }
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedOrderId = intent.getStringExtra(EXTRA_ORDER_ID)
        deliverRequestedOrder()
    }

    override fun onDestroy() {
        webView?.apply {
            removeJavascriptInterface("AndroidGestor")
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun requestNotificationsIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ORDER_ID = "open_order_id"
        private const val HYBRID_URL = "https://appassets.androidplatform.net/assets/web/index.html"
    }
}
