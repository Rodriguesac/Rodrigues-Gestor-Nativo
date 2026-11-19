package com.rodrigues.comandos

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var commandText: TextView
    private lateinit var speakButton: Button
    private lateinit var progress: ProgressBar

    private var pendingCommand: String? = null
    private var busy = false
    private var tts: TextToSpeech? = null

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            setBusy(false)
            if (result.resultCode != Activity.RESULT_OK) {
                setStatus("Comando cancelado.")
                return@registerForActivityResult
            }

            @Suppress("DEPRECATION")
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()

            if (transcript.isBlank()) {
                announce("Não consegui ouvir o comando.")
            } else {
                executeOrAuthorize(transcript)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
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

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(38), dp(24), dp(24))
            setBackgroundColor(Color.rgb(248, 246, 250))
        }

        val title = TextView(this).apply {
            text = "Rodrigues Comandos"
            textSize = 28f
            setTextColor(Color.rgb(91, 43, 130))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Controle rápido do cardápio e da loja"
            textSize = 16f
            setTextColor(Color.rgb(96, 88, 102))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        }

        statusText = TextView(this).apply {
            text = "Pronto para receber um comando."
            textSize = 18f
            setTextColor(Color.rgb(45, 40, 48))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(8))
        }

        commandText = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(Color.rgb(112, 104, 116))
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(18))
        }

        progress = ProgressBar(this).apply {
            visibility = android.view.View.GONE
        }

        speakButton = Button(this).apply {
            text = "FALAR COMANDO"
            textSize = 16f
            setOnClickListener { beginVoiceFlow() }
        }

        val examples = TextView(this).apply {
            text = "Exemplos:\n\nPausar morango\nReativar Nutella\nAbrir loja\nFechar loja"
            textSize = 16f
            setTextColor(Color.rgb(96, 88, 102))
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, 0)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            commandText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(progress)
        root.addView(
            speakButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58),
            ).apply {
                topMargin = dp(18)
            },
        )
        root.addView(
            examples,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        setContentView(root)
    }

    private fun handleIntent(intent: Intent?) {
        val directCommand = intent?.getStringExtra("command")?.trim().orEmpty()

        if (directCommand.isNotBlank()) {
            window.decorView.postDelayed(
                { executeOrAuthorize(directCommand) },
                250L,
            )
            return
        }

        val feature = intent?.getStringExtra("featureParam").orEmpty()
        val deepLink =
            intent?.data?.scheme == "rodriguescomandos" &&
                intent.data?.host == "voice"

        if (feature.isNotBlank() || deepLink || intent?.action == Intent.ACTION_MAIN) {
            window.decorView.postDelayed({ beginVoiceFlow() }, 350L)
        }
    }

    private fun beginVoiceFlow() {
        if (busy) return

        if (VoiceAdminApi.hasValidSession(this)) {
            launchRecognizer()
        } else {
            pendingCommand = null
            showPinDialog()
        }
    }

    private fun executeOrAuthorize(command: String) {
        if (busy) return

        if (VoiceAdminApi.hasValidSession(this)) {
            execute(command)
        } else {
            pendingCommand = command
            showPinDialog()
        }
    }

    private fun showPinDialog() {
        if (isFinishing) return

        val input = EditText(this).apply {
            hint = "PIN de 5 números"
            inputType =
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Autorizar Rodrigues Comandos")
            .setMessage("Digite o PIN do GADM. O PIN não fica salvo no aparelho.")
            .setView(input)
            .setNegativeButton("Cancelar") { _, _ ->
                pendingCommand = null
                setStatus("Autorização cancelada.")
            }
            .setPositiveButton("Entrar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().filter(Char::isDigit)
                if (pin.length != 5) {
                    input.error = "Digite os 5 números"
                    return@setOnClickListener
                }

                setBusy(true)
                setStatus("Autorizando…")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                VoiceAdminApi.login(
                    context = this,
                    pin = pin,
                    onDone = {
                        setBusy(false)
                        dialog.dismiss()
                        val command = pendingCommand
                        pendingCommand = null

                        if (command.isNullOrBlank()) {
                            launchRecognizer()
                        } else {
                            execute(command)
                        }
                    },
                    onError = { error ->
                        setBusy(false)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = error.message ?: "PIN inválido"
                        setStatus("Falha na autorização.")
                    },
                )
            }
        }

        dialog.show()
    }

    private fun launchRecognizer() {
        val recognizer = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Diga: pausar morango, reativar Nutella, abrir loja ou fechar loja",
            )
        }

        try {
            setBusy(true)
            setStatus("Ouvindo…")
            voiceLauncher.launch(recognizer)
        } catch (_: ActivityNotFoundException) {
            setBusy(false)
            announce("O reconhecimento de voz não está disponível neste aparelho.")
        }
    }

    private fun execute(command: String) {
        val clean = command.trim()
        if (clean.isBlank() || busy) return

        commandText.text = "“$clean”"
        setStatus("Executando…")
        setBusy(true)

        VoiceAdminApi.execute(
            context = this,
            transcript = clean,
            onResult = { result ->
                setBusy(false)
                announce(result.message)
            },
            onError = { error ->
                setBusy(false)
                announce(error.message ?: "Erro ao executar o comando.")

                if (error is VoiceSessionExpiredException) {
                    pendingCommand = clean
                    showPinDialog()
                }
            },
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        speakButton.isEnabled = !value
        progress.visibility =
            if (value) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun announce(message: String) {
        setStatus(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "rodrigues_comandos_result",
        )
    }
}
