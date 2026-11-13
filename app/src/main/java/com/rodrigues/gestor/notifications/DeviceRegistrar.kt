package com.rodrigues.gestor.notifications

import android.content.Context
import android.os.Build
import com.rodrigues.gestor.BuildConfig
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.NativeCatalogApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Não registra tokens de aparelhos sem login na central de pedidos. */
object PushRegistrationPolicy {
    fun allowed(isPreview: Boolean, pin: String, token: String): Boolean =
        !isPreview && pin.length == 6 && pin.all(Char::isDigit) && token.isNotBlank()
}

/**
 * Fluxo FCM v2: sessão 12h da gestor-app-api validada no servidor.
 * O endpoint público antigo permanece exclusivo de apps já instalados.
 */
object DeviceRegistrar {
    private const val ENDPOINT =
        "https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-device-register-v2"
    private const val PREFS = "gestor20_push_registration_v2"

    fun register(context: Context, token: String) {
        val app = context.applicationContext
        if (BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".preview")) return
        if (GestorCredentials.pin.length != 6) GestorCredentials.load(app)
        if (!PushRegistrationPolicy.allowed(false, GestorCredentials.pin, token)) {
            return
        }
        NativeCatalogApi.withValidSession(onSuccess = { session ->
            thread(name = "GestorDeviceRegisterV2") {
                try {
                    val body = JSONObject()
                        .put("token", token)
                        .put("session_token", session)
                        .put("package", "com.rodrigues.gestor")
                        .put("model", (Build.MANUFACTURER + " " + Build.MODEL).take(160))
                        .put("sdk", Build.VERSION.SDK_INT)
                        .put("app_version", BuildConfig.VERSION_NAME)
                    val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8_000
                        readTimeout = 12_000
                        useCaches = false
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        connection.outputStream.use {
                            it.write(body.toString().toByteArray(Charsets.UTF_8))
                        }
                        val code = connection.responseCode
                        val response = (if (code in 200..299) connection.inputStream
                            else connection.errorStream)?.bufferedReader()?.use {
                            it.readText().take(2048)
                        }.orEmpty()
                        if (code !in 200..299 ||
                            !JSONObject(response.ifBlank { "{}" }).optBoolean("ok", false)) {
                            throw IllegalStateException("Servidor recusou o cadastro (HTTP $code).")
                        }
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong("last_ok_at", System.currentTimeMillis())
                            .putString("status", "Cadastrado no servidor")
                            .apply()
                    } finally {
                        connection.disconnect()
                    }
                } catch (_: Throwable) {
                    // Sem armazenar token nem sessão em texto puro, nem imprimir dados de acesso.
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("status", "Cadastro não confirmado. Verifique a conexão e faça login.")
                        .apply()
                }
            }
        }, onError = {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("status", "Sessão indisponível. Acesse o Gestor para reconectar.")
                .apply()
        })
    }

    fun status(context: Context): String =
        if (BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".preview"))
            "Prévia: este aparelho não registra notificações de produção."
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("status", "Aguardando cadastro após login.")
            ?: "Aguardando cadastro após login."

    fun lastRegisteredAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_ok_at", 0L)
}
