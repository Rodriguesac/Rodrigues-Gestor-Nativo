package com.rodrigues.gestor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.LaunchedEffect
import com.rodrigues.gestor.ui.HybridGestorView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.rodrigues.gestor.data.SupabaseOrdersApi
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.notifications.AlertPreferences
import com.rodrigues.gestor.notifications.DeviceRegistrar
import com.rodrigues.gestor.notifications.FloatingPanelController
import com.rodrigues.gestor.notifications.GestorConnectionService
import com.rodrigues.gestor.notifications.NotificationHelper
import com.rodrigues.gestor.ui.GestorApp
import com.rodrigues.gestor.ui.theme.RodriguesGestorTheme
import com.rodrigues.gestor.voice.VoiceAdminApi
import com.rodrigues.gestor.voice.VoiceSessionExpiredException
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var requestedOrderId by mutableStateOf<String?>(null)
    private var appStarted = false
    private var showNativeTools by mutableStateOf(false)
    private var hybridView: HybridGestorView? = null
    private var voiceBusy by mutableStateOf(false)
    private var textToSpeech: TextToSpeech? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        GestorConnectionService.start(this)
    }

    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        voiceBusy = false
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        @Suppress("DEPRECATION")
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()

        if (transcript.isBlank()) {
            announce("Não consegui ouvir o comando.")
        } else {
            executeVoiceCommand(transcript)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrderId = intent?.getStringExtra(EXTRA_ORDER_ID)
        if (GestorCredentials.load(this).length == 6) {
            startGestor()
        } else {
            requestOperatorPin()
        }
    }

    private fun requestOperatorPin() {
        val input = EditText(this).apply {
            hint = "PIN de 6 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Acesso do Gestor")
            .setMessage("Digite o PIN de operador para acessar os pedidos.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString().orEmpty().filter { it.isDigit() }
                if (pin.length != 6) {
                    input.error = "Digite os 6 números do PIN"
                    return@setOnClickListener
                }
                GestorCredentials.save(this, pin)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                SupabaseOrdersApi.ping({
                    dialog.dismiss()
                    startGestor()
                }, { error ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    input.error = error.message ?: "Não foi possível entrar"
                })
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
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("pt", "BR")
            }
        }

        setContent {
            RodriguesGestorTheme {
                Box(Modifier.fillMaxSize()) {
                    if (showNativeTools) {
                        BackHandler { showNativeTools = false }
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            TextButton(onClick = { showNativeTools = false }) { Text("‹ Voltar ao Gestor") }
                            Box(Modifier.weight(1f)) {
                                GestorApp(requestedOrderId = null, startInSettings = true, onRequestedOrderConsumed = {})
                            }
                        }
                    } else {
                        BackHandler { hybridView?.goBackOrExit { moveTaskToBack(true) } }
                        AndroidView(
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            factory = {
                                val view = hybridView ?: HybridGestorView(this@MainActivity, { beginVoiceFlow() }, { showNativeTools = true }).also { hybridView = it }
                                (view.parent as? ViewGroup)?.removeView(view)
                                view
                            },
                        )
                        LaunchedEffect(requestedOrderId) {
                            hybridView?.showOrder(requestedOrderId)
                            requestedOrderId = null
                        }
                    }
                    if (showNativeTools) FloatingActionButton(
                        onClick = { beginVoiceFlow() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 92.dp)
                            .size(50.dp),
                        containerColor = Color(0xFFEA1D2C),
                        contentColor = Color.White,
                    ) {
                        if (voiceBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(21.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = "Comando de voz")
                        }
                    }
                }
            }
        }

        if (isVoiceIntent(intent)) {
            window.decorView.post { beginVoiceFlow() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appStarted && GestorCredentials.pin.length == 6) {
            hybridView?.refreshOrders()
            GestorConnectionService.start(this)
            if (AlertPreferences.floatingPanel(this)) {
                FloatingPanelController.sync(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedOrderId = intent.getStringExtra(EXTRA_ORDER_ID)
        if (appStarted && isVoiceIntent(intent)) {
            window.decorView.post { beginVoiceFlow() }
        }
    }

    override fun onDestroy() {
        hybridView?.destroy()
        hybridView = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    private fun beginVoiceFlow() {
        if (voiceBusy) return
        if (VoiceAdminApi.hasValidSession(this)) {
            launchVoiceInput()
        } else {
            showGadmPinDialog()
        }
    }

    private fun showGadmPinDialog() {
        val horizontal = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "PIN de 5 números"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        val holder = FrameLayout(this).apply {
            setPadding(horizontal, 0, horizontal, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ativar comando por voz")
            .setMessage("Digite o PIN do GADM. O PIN não fica salvo no aparelho.")
            .setView(holder)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().filter(Char::isDigit)
                if (pin.length != 5) {
                    input.error = "Digite os 5 números"
                    return@setOnClickListener
                }

                voiceBusy = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                VoiceAdminApi.login(
                    context = this,
                    pin = pin,
                    onDone = {
                        voiceBusy = false
                        dialog.dismiss()
                        launchVoiceInput()
                    },
                    onError = { error ->
                        voiceBusy = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = error.message ?: "PIN inválido"
                        input.requestFocus()
                    },
                )
            }
        }
        dialog.show()
    }

    private fun launchVoiceInput() {
        val recognizer = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Diga: fechar loja, abrir loja, pausar maçã ou reativar maçã",
            )
        }

        try {
            voiceBusy = true
            voiceInput.launch(recognizer)
        } catch (_: ActivityNotFoundException) {
            voiceBusy = false
            announce("O reconhecimento de voz do Android não está disponível neste aparelho.")
        }
    }

    private fun executeVoiceCommand(transcript: String) {
        voiceBusy = true
        VoiceAdminApi.execute(
            context = this,
            transcript = transcript,
            onResult = { result ->
                voiceBusy = false
                announce(result.message)
            },
            onError = { error ->
                voiceBusy = false
                announce(error.message ?: "Erro ao executar o comando por voz.")
                if (error is VoiceSessionExpiredException) {
                    showGadmPinDialog()
                }
            },
        )
    }

    private fun announce(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "gestor_voice_result")
    }

    private fun isVoiceIntent(intent: Intent?): Boolean =
        intent?.data?.scheme == "rodriguesgestor" && intent.data?.host == "voice"

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ORDER_ID = "open_order_id"
    }
}
