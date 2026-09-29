package com.rodrigues.gestor.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rodrigues.gestor.R
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale

class VoiceAssistantService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var commandEngine: VoiceCommandEngine? = null
    private var armedUntil = 0L
    private var lastHandled = ""
    private var lastHandledAt = 0L
    private var speaking = false
    private val wakeAliases = listOf("rodrigues", "rodrigue", "rodrigues gestor")

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startAsForeground()
        commandEngine = VoiceCommandEngine(this, ::speak)
        tts = TextToSpeech(this, this)
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (model != null && speechService == null) startRecognizer()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rodrigues Voz ativo")
            .setContentText("Diga “Rodrigues” e depois o comando.")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Rodrigues Voz",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Assistente de voz do Rodrigues Gestor"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun loadModel() {
        StorageService.unpack(
            this,
            "model-pt",
            "model-pt-runtime",
            { loaded ->
                model = loaded
                startRecognizer()
            },
            { error ->
                speak("Não consegui carregar o reconhecimento de voz. " + error.message.orEmpty())
            },
        )
    }

    private fun startRecognizer() {
        if (speechService != null) return
        val loaded = model ?: return
        try {
            val recognizer = Recognizer(loaded, 16_000.0f)
            speechService = SpeechService(recognizer, 16_000.0f).also {
                it.startListening(this)
            }
        } catch (_: Throwable) {
            speak("Falha ao iniciar o microfone do Rodrigues Voz.")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (speaking) return
        val phrase = extract(hypothesis, "partial")
        if (phrase.isBlank()) return
        if (!isArmed() && wakeDetected(phrase)) {
            arm()
        }
    }

    override fun onResult(hypothesis: String?) {
        processFinal(extract(hypothesis, "text"))
    }

    override fun onFinalResult(hypothesis: String?) {
        processFinal(extract(hypothesis, "text"))
    }

    override fun onError(exception: Exception?) {
        main.postDelayed({
            if (isRunning && speechService == null) startRecognizer()
        }, 800L)
    }

    override fun onTimeout() = Unit

    private fun processFinal(raw: String) {
        if (speaking) return
        val phrase = VoiceStateStore.normalize(raw)
        if (phrase.isBlank()) return

        val hasWake = wakeDetected(phrase)
        val followUp = commandEngine?.expectsFollowUp() == true
        if (!hasWake && !isArmed() && !followUp) return

        val command = if (hasWake) stripWake(phrase) else phrase
        if (hasWake && command.isBlank()) {
            arm()
            return
        }
        if (command.isBlank()) return

        val now = System.currentTimeMillis()
        if (command == lastHandled && now - lastHandledAt < 1_800L) return
        lastHandled = command
        lastHandledAt = now
        armedUntil = 0L
        commandEngine?.handle(command)
    }

    private fun wakeDetected(value: String): Boolean {
        val normalized = VoiceStateStore.normalize(value)
        return wakeAliases.any { alias ->
            normalized == alias || normalized.startsWith(alias + " ") || normalized.contains(" " + alias + " ")
        }
    }

    private fun stripWake(value: String): String {
        val normalized = VoiceStateStore.normalize(value)
        for (alias in wakeAliases.sortedByDescending { it.length }) {
            val index = normalized.indexOf(alias)
            if (index >= 0) return normalized.substring(index + alias.length).trim()
        }
        return normalized
    }

    private fun arm() {
        armedUntil = System.currentTimeMillis() + 8_000L
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 110)
                main.postDelayed({ release() }, 180L)
            }
        }
    }

    private fun isArmed(): Boolean =
        System.currentTimeMillis() <= armedUntil || commandEngine?.expectsFollowUp() == true

    private fun extract(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(key) }.getOrDefault("")
    }

    private fun speak(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return
        main.post {
            val engine = tts ?: return@post
            speaking = true
            speechService?.setPause(true)
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts?.language = Locale("pt", "BR")
        tts?.setSpeechRate(1.03f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                main.post {
                    speaking = false
                    speechService?.setPause(false)
                    if (commandEngine?.expectsFollowUp() == true) {
                        armedUntil = System.currentTimeMillis() + 12_000L
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone(utteranceId)
            }
        })
    }

    override fun onDestroy() {
        isRunning = false
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "rodrigues_voice_v1"
        private const val NOTIFICATION_ID = 7311
        private const val UTTERANCE_ID = "rodrigues_voice_reply"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoiceAssistantService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceAssistantService::class.java))
        }
    }
}
