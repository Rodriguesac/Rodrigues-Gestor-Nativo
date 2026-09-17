package com.rodrigues.gestor.data

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SupabaseCatalogApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-builder-catalog"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun listenProducts(
        onData: (List<CatalogProduct>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 5_000L,
    ): ListenerRegistration {
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay({
            if (stopped.get()) return@scheduleWithFixedDelay
            try {
                val rows = fetchAll()
                mainHandler.post { if (!stopped.get()) onData(rows) }
            } catch (error: Throwable) {
                mainHandler.post { if (!stopped.get()) onError(error) }
            }
        }, 0L, intervalMs.coerceAtLeast(3_000L), TimeUnit.MILLISECONDS)
        return object : ListenerRegistration {
            override fun remove() {
                stopped.set(true)
                executor.shutdownNow()
            }
        }
    }

    fun setAvailable(
        product: CatalogProduct,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val payload = JSONObject().put("action", "toggle").put("available", available)
        when {
            product.id.startsWith("product:") -> {
                payload.put("kind", "product")
                payload.put("productId", product.id.removePrefix("product:"))
            }
            product.id.startsWith("builder:") -> {
                val parts = product.id.split(':', limit = 3)
                if (parts.size != 3) {
                    onError(IllegalArgumentException("Item do Monte seu Pedido inválido."))
                    return
                }
                payload.put("kind", "builder")
                payload.put("collection", parts[1])
                payload.put("documentId", parts[2])
            }
            else -> {
                onError(IllegalArgumentException("Item de cardápio inválido."))
                return
            }
        }
        Thread {
            try {
                request(payload)
                mainHandler.post(onDone)
            } catch (error: Throwable) {
                mainHandler.post { onError(error) }
            }
        }.start()
    }

    private fun fetchAll(): List<CatalogProduct> {
        val response = request(JSONObject().put("action", "list"))
        val result = mutableListOf<CatalogProduct>()
        parseRows(response.optJSONArray("products") ?: JSONArray(), result, builder = false)
        parseRows(response.optJSONArray("builder") ?: JSONArray(), result, builder = true)
        return result.sortedWith(compareBy<CatalogProduct> { it.category.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun parseRows(rows: JSONArray, output: MutableList<CatalogProduct>, builder: Boolean) {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").trim()
            if (id.isBlank()) continue
            val rawCategory = row.optString("category").trim()
            val category = if (builder) builderCategory(rawCategory) else rawCategory.ifBlank { "Produtos" }
            output += CatalogProduct(
                id = id,
                name = row.optString("name").ifBlank { id.substringAfterLast(':') },
                category = category,
                available = row.optBoolean("available", true),
            )
        }
    }

    private fun builderCategory(collection: String): String = when (collection) {
        "bases" -> "Monte seu Pedido • Bases"
        "coberturas" -> "Monte seu Pedido • Coberturas"
        "acompanhamentos_gratis" -> "Monte seu Pedido • Acompanhamentos"
        "adicionais" -> "Monte seu Pedido • Adicionais"
        "addons" -> "Monte seu Pedido • Add-ons"
        "utensilios" -> "Monte seu Pedido • Utensílios"
        else -> "Monte seu Pedido"
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
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            if (code == 401) {
                GestorCredentials.clear()
                throw IllegalStateException("PIN do Gestor inválido. Abra o aplicativo novamente.")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error").ifBlank { "Falha ao acessar o cardápio do Supabase ($code)." })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
