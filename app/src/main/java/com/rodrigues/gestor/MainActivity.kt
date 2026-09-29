package com.rodrigues.gestor

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodrigues.gestor.voice.VoiceAdminApi
import com.rodrigues.gestor.voice.VoiceSessionExpiredException
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Pronto para receber um comando.")
    private var lastCommand by mutableStateOf("")
    private var pendingCommand: String? = null
    private var tts: TextToSpeech? = null

    private val voiceInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            busy = false
            if (result.resultCode != Activity.RESULT_OK) {
                status = "Comando cancelado."
                return@registerForActivityResult
            }

            @Suppress("DEPRECATION")
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()

            if (transcript.isBlank()) announce("Não consegui ouvir o comando.")
            else executeOrLogin(transcript)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) tts?.language = Locale("pt", "BR")
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF8F6FA)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "Rodrigues Comandos",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF5B2B82),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pausar e ativar itens • Abrir e fechar a loja",
                            textAlign = TextAlign.Center,
                            color = Color(0xFF655D69),
                        )
                        Spacer(Modifier.height(30.dp))
                        Text(
                            status,
                            textAlign = TextAlign.Center,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (lastCommand.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("“$lastCommand”", color = Color(0xFF777077))
                        }
                        Spacer(Modifier.height(26.dp))
                        Button(
                            onClick = { beginVoiceFlow() },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                androidx.compose.material3.Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.padding(5.dp))
                                Text("FALAR COMANDO", fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                        Text(
                            "Exemplos:\nPausar morango\nReativar Nutella\nAbrir loja\nFechar loja",
                            textAlign = TextAlign.Center,
                            lineHeight = 23.sp,
                            color = Color(0xFF655D69),
                        )
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val direct = intent?.getStringExtra("command")?.trim().orEmpty()
        if (direct.isNotBlank()) {
            window.decorView.postDelayed({ executeOrLogin(direct) }, 250)
            return
        }
        window.decorView.postDelayed({ beginVoiceFlow() }, 350)
    }

    private fun beginVoiceFlow() {
        if (busy) return
        if (VoiceAdminApi.hasValidSession(this)) launchVoiceInput()
        else {
            pendingCommand = null
            showPinDialog()
        }
    }

    private fun executeOrLogin(command: String) {
        if (VoiceAdminApi.hasValidSession(this)) execute(command)
        else {
            pendingCommand = command
            showPinDialog()
        }
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            hint = "PIN de 5 números"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Autorizar Rodrigues Comandos")
            .setMessage("Digite o PIN do GADM. O PIN não fica salvo no aparelho.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().filter(Char::isDigit)
                if (pin.length != 5) {
                    input.error = "Digite os 5 números"
                    return@setOnClickListener
                }

                busy = true
                status = "Autorizando…"
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                VoiceAdminApi.login(
                    context = this,
                    pin = pin,
                    onDone = {
                        busy = false
                        dialog.dismiss()
                        val command = pendingCommand
                        pendingCommand = null
                        if (command.isNullOrBlank()) launchVoiceInput() else execute(command)
                    },
                    onError = { error ->
                        busy = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = error.message ?: "PIN inválido"
                        status = "Falha na autorização."
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
                "Diga: pausar morango, reativar Nutella, abrir loja ou fechar loja",
            )
        }

        try {
            busy = true
            status = "Ouvindo…"
            voiceInput.launch(recognizer)
        } catch (_: ActivityNotFoundException) {
            busy = false
            announce("O reconhecimento de voz não está disponível neste aparelho.")
        }
    }

    private fun execute(command: String) {
        if (busy) return
        val clean = command.trim()
        lastCommand = clean
        status = "Executando…"
        busy = true

        VoiceAdminApi.execute(
            context = this,
            transcript = clean,
            onResult = { result ->
                busy = false
                announce(result.message)
            },
            onError = { error ->
                busy = false
                announce(error.message ?: "Erro ao executar o comando.")
                if (error is VoiceSessionExpiredException) {
                    pendingCommand = clean
                    showPinDialog()
                }
            },
        )
    }

    private fun announce(message: String) {
        status = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "rodrigues_comandos_result")
    }
}
