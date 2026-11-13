package com.rodrigues.gestor.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Usa a MESMA gestor-app-api do Gestor20 web. Nenhuma segunda tabela ou
 * "cardápio de demonstração": todas as mudanças são confirmadas no servidor.
 */
data class CatalogItem(
    val id: String,
    val collection: String,
    val name: String,
    val price: Double,
    val available: Boolean,
    val active: Boolean,
    val description: String,
    val imageUrl: String,
    val sortOrder: Int,
    val compareAtPrice: Double = 0.0
) {
    val forSale: Boolean get() = available && active
}

data class CatalogSnapshot(val items: List<CatalogItem>, val canEdit: Boolean)

object NativeCatalogApi {
    private const val ENDPOINT = "https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-app-api"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var token = ""
    private var canEdit = false

    private fun post(body: JSONObject): JSONObject {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = if (content.isBlank()) JSONObject() else JSONObject(content)
            if (status !in 200..299 || !result.optBoolean("ok", false)) {
                throw CatalogApiError(status, result.optString("message").ifBlank {
                    result.optString("error").ifBlank { "Não foi possível consultar o cardápio." }
                })
            }
            return result
        } finally { connection.disconnect() }
    }

    private class CatalogApiError(val status: Int, message: String) : Exception(message)

    @Synchronized
    private fun call(action: String, data: JSONObject = JSONObject()): JSONObject {
        fun login() {
            val pin = GestorCredentials.pin
            if (pin.length != 6) throw IllegalStateException("Abra o Gestor e faça login novamente.")
            val response = post(JSONObject().put("action", "login").put("pin", pin))
            token = response.getString("session_token")
            canEdit = response.optJSONObject("operator")?.optString("role") == "owner"
        }
        if (token.isEmpty()) login()
        fun invoke() = post(JSONObject(data.toString()).put("action", action).put("session_token", token))
        return try { invoke() } catch (error: CatalogApiError) {
            if (error.status != 401) throw error
            token = ""
            login()
            invoke()
        }
    }

    fun load(context: Context, onSuccess: (CatalogSnapshot) -> Unit, onError: (Throwable) -> Unit) {
        val app = context.applicationContext
        io.execute {
            try {
                val products = call("products").optJSONArray("products") ?: JSONArray()
                val builder = call("builder_catalog").optJSONArray("items") ?: JSONArray()
                val all = mutableListOf<CatalogItem>()
                for (i in 0 until products.length()) {
                    val row = products.optJSONObject(i) ?: continue
                    all += CatalogItem(
                        id = row.optString("id"), collection = "products", name = row.optString("name"),
                        price = row.optDouble("price", 0.0), available = row.optBoolean("available", true),
                        active = row.optBoolean("active", true), description = row.optString("description"),
                        imageUrl = row.optString("image_url"), sortOrder = row.optInt("sort_order", 0),
                        compareAtPrice = row.optDouble("compare_at_price", 0.0)
                    )
                }
                for (i in 0 until builder.length()) {
                    val row = builder.optJSONObject(i) ?: continue
                    val data = row.optJSONObject("data") ?: JSONObject()
                    all += CatalogItem(
                        id = row.optString("id"), collection = row.optString("collection"),
                        name = row.optString("name"), price = row.optDouble("price", 0.0),
                        available = row.optBoolean("available", true),
                        active = data.optBoolean("ativo", true), description = data.optString("descricao"),
                        imageUrl = data.optString("imagem"), sortOrder = row.optInt("order", 0)
                    )
                }
                val items = all.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .sortedWith(compareBy({ it.collection }, { it.sortOrder }, { it.name }))
                app.getSharedPreferences("gestor20_catalog_cache", Context.MODE_PRIVATE)
                    .edit().putString("items", gson.toJson(items)).apply()
                val snapshot = CatalogSnapshot(items, canEdit)
                main.post { onSuccess(snapshot) }
            } catch (error: Throwable) { main.post { onError(error) } }
        }
    }

    fun cached(context: Context): List<CatalogItem> = try {
        val saved = context.getSharedPreferences("gestor20_catalog_cache", Context.MODE_PRIVATE)
            .getString("items", null) ?: return emptyList()
        val type = object : TypeToken<List<CatalogItem>>() {}.type
        gson.fromJson<List<CatalogItem>>(saved, type) ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    fun setAvailability(
        item: CatalogItem, available: Boolean, onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        io.execute {
            try {
                if (!canEdit && token.isNotEmpty()) throw SecurityException("Somente o dono pode editar o cardápio.")
                val args = JSONObject().put("available", available)
                val action = if (item.collection == "products") "update_product" else "update_builder_item"
                if (item.collection == "products") {
                    args.put("product_id", item.id)
                    if (available) args.put("active", true)
                } else args.put("collection", item.collection).put("document_id", item.id)
                call(action, args)
                main.post(onSuccess)
            } catch (error: Throwable) { main.post { onError(error) } }
        }
    }

    fun setProductPrice(
        item: CatalogItem, price: Double, onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        io.execute {
            try {
                require(item.collection == "products") { "Preços de montagem requerem edição por tamanho." }
                require(price.isFinite() && price in 0.0..10000.0)
                if (!canEdit && token.isNotEmpty()) throw SecurityException("Somente o dono pode alterar preços.")
                val data = JSONObject().put("id", item.id).put("name", item.name).put("price", price)
                    .put("description", item.description).put("image_url", item.imageUrl)
                    .put("sort_order", item.sortOrder).put("active", item.active).put("available", item.available)
                if (item.compareAtPrice > 0) data.put("compare_at_price", item.compareAtPrice)
                call("catalog_save", data)
                main.post(onSuccess)
            } catch (error: Throwable) { main.post { onError(error) } }
        }
    }

    fun clear(context: Context) {
        token = ""
        canEdit = false
        context.getSharedPreferences("gestor20_catalog_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
