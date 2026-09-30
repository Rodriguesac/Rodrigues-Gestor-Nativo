package com.rodrigues.gestor.notifications

import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object DeviceRegistrar {
    private const val ENDPOINT = "https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-device-register"
    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZkcXF3ZHBscHJ6cHF1ZnBnZHJtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA3NjkwNzcsImV4cCI6MjEwNjM0NTA3N30.4eKobxZb_ULIykZBbV4xQHb9tCG8YochcC17MurqBY0"

    fun register(token: String) {
        if (token.isBlank()) return

        FirebaseMessaging.getInstance().subscribeToTopic("gestor-pedidos")

        thread(name = "GestorDeviceRegister") {
            runCatching {
                val payload =
                    "{\"token\":" + json(token) +
                    ",\"package\":\"com.rodrigues.gestor\"" +
                    ",\"model\":" + json(Build.MANUFACTURER + " " + Build.MODEL) +
                    ",\"sdk\":" + Build.VERSION.SDK_INT +
                    ",\"app_version\":\"3.3.1-mobile\"}"

                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("apikey", ANON_KEY)
                    setRequestProperty("Authorization", "Bearer " + ANON_KEY)
                }
                try {
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
