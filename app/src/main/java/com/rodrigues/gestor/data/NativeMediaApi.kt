package com.rodrigues.gestor.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Fotos do cardápio são enviadas somente após autenticação do dono. */
object NativeMediaApi {
    private const val ENDPOINT =
        "https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-media-upload"
    private const val MAX_BYTES = 5 * 1024 * 1024
    private val io = Executors.newSingleThreadExecutor()

    fun upload(context: Context, uri: Uri,
               onSuccess: (String) -> Unit, onError: (Throwable) -> Unit) {
        val app = context.applicationContext
        NativeCatalogApi.withValidSession(onSuccess = { session ->
            io.execute {
                try {
                    val resolver = app.contentResolver
                    val type = resolver.getType(uri) ?: throw IOException("Imagem sem tipo reconhecido.")
                    require(type in setOf("image/jpeg","image/png","image/webp")) {
                        "Escolha uma imagem JPG, PNG ou WebP."
                    }
                    val bytes = resolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16_384)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > MAX_BYTES) {
                                throw IOException("Imagem maior que 5 MB. Reduza a resolução.")
                            }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: throw IOException("Não foi possível ler a imagem selecionada.")
                    require(bytes.isNotEmpty()) { "Imagem vazia." }
                    val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 12_000
                        readTimeout = 25_000
                        doOutput = true
                        useCaches = false
                        setRequestProperty("Content-Type", type)
                        setRequestProperty("X-Gestor-Session", session)
                        setRequestProperty("Accept", "application/json")
                        setFixedLengthStreamingMode(bytes.size)
                    }
                    try {
                        connection.outputStream.use { it.write(bytes) }
                        val status = connection.responseCode
                        val response = (if (status in 200..299) connection.inputStream
                            else connection.errorStream)?.bufferedReader()?.use {
                                it.readText().take(4096)
                            }.orEmpty()
                        val data = JSONObject(response.ifBlank { "{}" })
                        if (status !in 200..299 || !data.optBoolean("ok", false))
                            throw IOException("Upload recusado (HTTP $status): " +
                                data.optString("error").ifBlank { "erro desconhecido" })
                        val url = data.optString("image_url")
                        require(url.startsWith("https://")) { "Servidor não retornou URL válida." }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess(url)
                        }
                    } finally { connection.disconnect() }
                } catch (error: Throwable) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onError(error)
                    }
                }
            }
        }, onError = onError)
    }
}
