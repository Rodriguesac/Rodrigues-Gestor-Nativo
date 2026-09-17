package com.rodrigues.gestor.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupabaseBuilderCatalogApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-builder-catalog"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchProducts(): List<CatalogProduct> {
        val response = request(JSONObject().put("action", "list"))
        val rows = response.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val collection = row.optString("collection_name").trim()
                val documentId = row.optString("document_id").trim()
                val data = row.optJSONObject("data") ?: JSONObject()
                if (collection.isBlank() || documentId.isBlank()) continue
                val name = data.optString("nome").ifBlank { data.optString("n") }.ifBlank { documentId }
                val category = when (collection) {
                    "bases" -> "Monte seu Pedido • Bases"
                    "coberturas" -> "Monte seu Pedido • Coberturas"
                    "acompanhamentos_gratis" -> "Monte seu Pedido • Acompanhamentos"
                    "adicionais" -> "Monte seu Pedido • Adicionais"
                    else -> "Monte seu Pedido"
                }
                val available = data.optBoolean("disponivel", true) &&
                    data.optBoolean("ativo", true) &&
                    data.optBoolean("active", true) &&
                    !data.optBoolean("pausado", false)
                add(
                    CatalogProduct(
                        id = "builder|$collection|$documentId",
                        name = name,
                        category = category,
                        available = available,
                    )
                )
            }
        }
    }

    fun setAvailable(
        compoundId: String,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val parts = compoundId.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != "builder") {
            onError(IllegalArgumentException("Item do Monte seu Pedido inválido."))
            return
        }
        Thread {
            try {
                request(
                    JSONObject()
                        .put("action", "toggle")
                        .put("collection", parts[1])
                        .put("documentId", parts[2])
                        .put("available", available)
                )
                mainHandler.post(onDone)
            } catch (error: Throwable) {
                mainHandler.post { onError(error) }
            }
        }.start()
    }

    private fun request(payload: JSONObject): JSONObject {
        val pin = GestorCredentials.pin.trim()
        if (pin.length != 6) throw IllegalStateException("PIN do Gestor não configurado.")
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-gestor-pin", pin)
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error").ifBlank { "Falha ao carregar Monte seu Pedido ($code)." })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
