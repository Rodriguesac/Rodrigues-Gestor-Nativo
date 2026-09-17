package com.rodrigues.gestor.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VoiceCommandResult(
    val message: String,
    val changed: Boolean,
)

class VoiceSessionExpiredException(message: String) : IllegalStateException(message)

object VoiceAdminApi {
    private const val LOGIN_ENDPOINT =
        "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gadm-catalog"
    private const val VOICE_ENDPOINT =
        "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-voice"
    private const val PREFS = "rodrigues_voice_admin"
    private const val KEY_SESSION = "session"
    private const val KEY_EXPIRES_AT = "expires_at"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasValidSession(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = preferences.getString(KEY_SESSION, "").orEmpty()
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        return token.isNotBlank() && expiresAt > (System.currentTimeMillis() / 1_000L) + 60L
    }

    fun login(
        context: Context,
        pin: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val cleanPin = pin.filter(Char::isDigit)
        if (cleanPin.length != 5) {
            onError(IllegalArgumentException("O PIN do GADM precisa ter 5 números."))
            return
        }

        Thread {
            try {
                val response = post(
                    endpoint = LOGIN_ENDPOINT,
                    payload = JSONObject()
                        .put("action", "login")
                        .put("pin", cleanPin),
                    session = null,
                )
                if (response.code !in 200..299 || !response.json.optBoolean("ok", false)) {
                    throw IllegalStateException(
                        response.json.optString("message")
                            .ifBlank { "Não foi possível entrar no GADM (" + response.code + ")." }
                    )
                }

                val session = response.json.optString("session").trim()
                val expiresAt = response.json.optLong("expires_at", 0L)
                if (session.isBlank() || expiresAt <= 0L) {
                    throw IllegalStateException("O Supabase não devolveu uma sessão administrativa válida.")
                }

                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSION, session)
                    .putLong(KEY_EXPIRES_AT, expiresAt)
                    .apply()
                mainHandler.post(onDone)
            } catch (error: Throwable) {
                mainHandler.post { onError(error) }
            }
        }.start()
    }

    fun execute(
        context: Context,
        transcript: String,
        onResult: (VoiceCommandResult) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) {
            onError(IllegalArgumentException("Nenhum comando foi reconhecido."))
            return
        }

        val session = session(context)
        if (session.isBlank()) {
            onError(VoiceSessionExpiredException("Informe o PIN do GADM para usar o comando por voz."))
            return
        }

        Thread {
            try {
                val response = post(
                    endpoint = VOICE_ENDPOINT,
                    payload = JSONObject()
                        .put("action", "execute")
                        .put("transcript", cleanTranscript),
                    session = session,
                )

                if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    clearSession(context)
                    throw VoiceSessionExpiredException(
                        response.json.optString("message")
                            .ifBlank { "Sessão expirada. Informe o PIN do GADM novamente." }
                    )
                }

                val message = response.json.optString("message").ifBlank {
                    if (response.code in 200..299) {
                        "Comando concluído."
                    } else {
                        "Não foi possível executar o comando (" + response.code + ")."
                    }
                }

                if (response.code in 200..299 && response.json.optBoolean("ok", false)) {
                    mainHandler.post { onResult(VoiceCommandResult(message, changed = true)) }
                } else if (response.code in listOf(400, 404, 409, 422)) {
                    mainHandler.post { onResult(VoiceCommandResult(message, changed = false)) }
                } else {
                    throw IllegalStateException(message)
                }
            } catch (error: Throwable) {
                mainHandler.post { onError(error) }
            }
        }.start()
    }

    fun clearSession(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun session(context: Context): String {
        if (!hasValidSession(context)) {
            clearSession(context)
            return ""
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SESSION, "")
            .orEmpty()
    }

    private data class HttpResult(
        val code: Int,
        val json: JSONObject,
    )

    private fun post(
        endpoint: String,
        payload: JSONObject,
        session: String?,
    ): HttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!session.isNullOrBlank()) {
                setRequestProperty("x-gadm-session", session)
            }
        }

        try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            return HttpResult(code, json)
        } finally {
            connection.disconnect()
        }
    }
}
