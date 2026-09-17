package com.rodrigues.gestor.data

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SupabaseOrdersApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-orders"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 2_500L,
    ): ListenerRegistration = poll(intervalMs, onData, onError, ::fetchOrders)

    fun listenCatalogProducts(
        onData: (List<CatalogProduct>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 5_000L,
    ): ListenerRegistration = poll(intervalMs, onData, onError, ::fetchProducts)

    fun listenDrivers(
        onData: (List<Driver>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 5_000L,
    ): ListenerRegistration = poll(intervalMs, onData, onError, ::fetchDrivers)

    fun listenOperation(
        onData: (StoreOperation) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 5_000L,
    ): ListenerRegistration = poll(intervalMs, onData, onError, ::fetchOperation)

    fun updateStatus(
        orderId: String,
        status: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject().put("action", "status").put("orderId", orderId).put("status", status),
        onDone,
        onError,
    )

    fun finishPickup(orderId: String, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(JSONObject().put("action", "finish_pickup").put("orderId", orderId), onDone, onError)

    fun cancelOrder(
        orderId: String,
        reason: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject().put("action", "cancel").put("orderId", orderId).put("reason", reason),
        onDone,
        onError,
    )

    fun setCatalogProductAvailable(
        productId: String,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject().put("action", "product_toggle").put("id", productId).put("available", available),
        onDone,
        onError,
    )

    fun setStoreOpen(open: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(JSONObject().put("action", "store_set").put("open", open), onDone, onError)

    private fun <T> poll(
        intervalMs: Long,
        onData: (T) -> Unit,
        onError: (Throwable) -> Unit,
        fetcher: () -> T,
    ): ListenerRegistration {
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay({
            if (stopped.get()) return@scheduleWithFixedDelay
            try {
                val data = fetcher()
                mainHandler.post { if (!stopped.get()) onData(data) }
            } catch (error: Throwable) {
                mainHandler.post { if (!stopped.get()) onError(error) }
            }
        }, 0L, intervalMs.coerceAtLeast(2_000L), TimeUnit.MILLISECONDS)
        return object : ListenerRegistration {
            override fun remove() {
                stopped.set(true)
                executor.shutdownNow()
            }
        }
    }

    private fun fetchOrders(): List<Order> {
        val response = request(JSONObject().put("action", "list").put("limit", 120))
        val rows = response.optJSONArray("orders") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue
                add(normalizeSupabaseOrder(id, row))
            }
        }.sortedByDescending { it.createdMillis }
    }

    private fun normalizeSupabaseOrder(id: String, row: JSONObject): Order {
        val rawPayload = row.optJSONObject("raw_payload")
        val raw = if (rawPayload != null) JSONObject(rawPayload.toString()) else JSONObject()

        putIfPresent(raw, "numeroPedido", row, "order_code")
        putIfPresent(raw, "codigoPedido", row, "order_code")
        putIfPresent(raw, "status", row, "status")
        putIfPresent(raw, "statusPedido", row, "status")
        putIfPresent(raw, "statusLoja", row, "status")
        putIfPresent(raw, "tipoPedido", row, "fulfillment_type")
        putIfPresent(raw, "subtotal", row, "subtotal")
        putIfPresent(raw, "frete", row, "delivery_fee")
        putIfPresent(raw, "taxaEntrega", row, "delivery_fee")
        putIfPresent(raw, "desconto", row, "discount")
        putIfPresent(raw, "total", row, "total")
        putIfPresent(raw, "observacao", row, "customer_note")
        putIfPresent(raw, "rastreamentoClienteHabilitado", row, "tracking_enabled")

        val created = parseSupabaseTime(row.optString("created_at"))
        if (created > 0L) raw.put("createdAt", created)

        row.optJSONObject("delivery_address")?.let { raw.put("endereco", JSONObject(it.toString())) }

        row.optJSONObject("customer")?.let { customer ->
            val client = JSONObject()
            putIfPresent(client, "nome", customer, "name")
            putIfPresent(client, "email", customer, "email")
            putIfPresent(client, "telefone", customer, "phone")
            putIfPresent(client, "uid", customer, "id")
            customer.optJSONObject("address")?.let { if (!raw.has("endereco")) raw.put("endereco", JSONObject(it.toString())) }
            raw.put("cliente", client)
        }

        val payment = row.optJSONObject("payment_details")?.let { JSONObject(it.toString()) } ?: JSONObject()
        putIfPresent(payment, "forma", row, "payment_method")
        putIfPresent(payment, "status", row, "payment_status")
        raw.put("pagamento", payment)

        val sourceItems = row.optJSONArray("items") ?: JSONArray()
        val legacyItems = JSONArray()
        for (i in 0 until sourceItems.length()) {
            val itemRow = sourceItems.optJSONObject(i) ?: continue
            val itemPayload = itemRow.optJSONObject("raw_payload")
            val item = if (itemPayload != null) JSONObject(itemPayload.toString()) else JSONObject()
            putIfPresent(item, "nome", itemRow, "name")
            putIfPresent(item, "titulo", itemRow, "name")
            putIfPresent(item, "quantidade", itemRow, "quantity")
            putIfPresent(item, "qtd", itemRow, "quantity")
            putIfPresent(item, "preco", itemRow, "unit_price")
            putIfPresent(item, "total", itemRow, "total_price")
            putIfPresent(item, "observacao", itemRow, "notes")
            if (!item.has("detalhes") && !item.has("linhas") && itemRow.has("modifiers") && !itemRow.isNull("modifiers")) {
                item.put("detalhes", itemRow.opt("modifiers"))
            }
            legacyItems.put(item)
        }
        raw.put("itens", legacyItems)

        row.optJSONObject("courier")?.let { courier ->
            val delivery = raw.optJSONObject("entrega")?.let { JSONObject(it.toString()) } ?: JSONObject()
            putIfPresent(delivery, "entregadorNome", courier, "name")
            putIfPresent(delivery, "entregadorTelefone", courier, "phone")
            putIfPresent(delivery, "status", courier, "status")
            raw.put("entrega", delivery)
            putIfPresent(raw, "entregadorNome", courier, "name")
        }

        return normalizeOrder(id, jsonObjectToMap(raw))
    }

    private fun fetchProducts(): List<CatalogProduct> {
        val response = request(JSONObject().put("action", "products_list"))
        val rows = response.optJSONArray("products") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue
                val metadata = row.optJSONObject("metadata")
                val category = sequenceOf(
                    metadata?.optString("categoriaNome"),
                    metadata?.optString("categoria"),
                    metadata?.optString("departamento"),
                    row.optString("category")
                ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
                add(
                    CatalogProduct(
                        id = id,
                        name = row.optString("name").ifBlank { metadata?.optString("nome").orEmpty().ifBlank { id } },
                        category = category,
                        available = row.optBoolean("active", true) && row.optBoolean("available", true) && metadata?.optBoolean("pausado", false) != true,
                    )
                )
            }
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun fetchDrivers(): List<Driver> {
        val response = request(JSONObject().put("action", "couriers_list"))
        val rows = response.optJSONArray("couriers") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue
                val map = jsonObjectToMap(row).toMutableMap()
                map["nome"] = row.optString("name")
                map["telefone"] = row.optString("phone")
                map["online"] = row.optBoolean("online", false)
                map["statusOperacional"] = row.optString("status")
                map["rastreamento"] = row.optBoolean("tracking_enabled", false)
                add(normalizeDriver(id, map))
            }
        }.sortedWith(compareByDescending<Driver> { it.available }.thenBy { it.name })
    }

    private fun fetchOperation(): StoreOperation {
        val response = request(JSONObject().put("action", "store_get"))
        val store = response.optJSONObject("store") ?: JSONObject()
        val value = store.optJSONObject("value") ?: JSONObject()
        return StoreOperation(
            open = value.optBoolean("lojaAberta", value.optBoolean("aberta", value.optBoolean("aceitarPedidos", true))),
            pausedUntilMillis = parseSupabaseTime(value.optString("pausaAte")),
            maintenance = value.optBoolean("manutencao", false),
            emergency = value.optBoolean("emergencia", false),
            closedMessage = value.optString("mensagemFechada").ifBlank { value.optString("closedMessage") },
            demandMessage = value.optString("mensagemDemanda").ifBlank { value.optString("demandMessage") },
            prepMinutes = value.optInt("tempoPreparo", value.optInt("prepMinutes", 25)).coerceIn(5, 180),
            raw = jsonObjectToMap(value),
        )
    }

    private fun runAction(payload: JSONObject, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        Thread {
            try {
                request(payload)
                mainHandler.post(onDone)
            } catch (error: Throwable) {
                mainHandler.post { onError(error) }
            }
        }.start()
    }

    private fun request(payload: JSONObject): JSONObject {
        val pin = GestorCredentials.pin.trim()
        if (pin.length != 6) throw IllegalStateException("PIN do Gestor não configurado. Feche e abra o aplicativo.")

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
            if (code == 401) {
                GestorCredentials.clear()
                throw IllegalStateException("PIN do Gestor inválido. Feche e abra o aplicativo para digitar novamente.")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error").ifBlank { "Falha ao acessar o Supabase ($code)." })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun putIfPresent(target: JSONObject, targetKey: String, source: JSONObject, sourceKey: String) {
        if (source.has(sourceKey) && !source.isNull(sourceKey)) target.put(targetKey, source.opt(sourceKey))
    }

    private fun parseSupabaseTime(value: String): Long {
        if (value.isBlank() || value == "null") return 0L
        value.toLongOrNull()?.let { return it }
        val normalized = value.replace(Regex("(\\.\\d{3})\\d+"), "$1")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ssXXX",
        )
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(normalized)?.time ?: 0L
            } catch (_: Throwable) { }
        }
        return 0L
    }

    private fun jsonObjectToMap(value: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonValue(value.opt(key))
        }
        return result
    }

    private fun jsonArrayToList(value: JSONArray): List<Any?> =
        (0 until value.length()).map { jsonValue(value.opt(it)) }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}
