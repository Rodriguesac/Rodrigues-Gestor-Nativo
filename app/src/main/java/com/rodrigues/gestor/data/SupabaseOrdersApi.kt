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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SupabaseOrdersApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-orders"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 3_000L,
    ): ListenerRegistration {
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay({
            if (stopped.get()) return@scheduleWithFixedDelay
            try {
                val rows = fetchOrders()
                mainHandler.post { if (!stopped.get()) onData(rows) }
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

    fun updateStatus(
        orderId: String,
        status: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject()
            .put("action", "status")
            .put("orderId", orderId)
            .put("status", status),
        onDone,
        onError,
    )

    fun finishPickup(orderId: String, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(
            JSONObject().put("action", "finish_pickup").put("orderId", orderId),
            onDone,
            onError,
        )

    fun cancelOrder(
        orderId: String,
        reason: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject()
            .put("action", "cancel")
            .put("orderId", orderId)
            .put("reason", reason),
        onDone,
        onError,
    )

    fun findOrder(id: String, onData: (Order) -> Unit, onError: (Throwable) -> Unit) {
        Thread {
            try {
                val order = fetchOrders().firstOrNull { it.id == id || it.number == id } ?: error("Pedido não encontrado. Atualize a lista.")
                mainHandler.post { onData(order) }
            } catch (e: Throwable) { mainHandler.post { onError(e) } }
        }.start()
    }

    fun ping(onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(JSONObject().put("action", "ping"), onDone, onError)

    fun setStoreOpen(open: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(JSONObject().put("action", "store_set").put("open", open), onDone, onError)

    fun listenStore(onData: (StoreOperation) -> Unit, onError: (Throwable) -> Unit): ListenerRegistration = poll({
        val value = request(JSONObject().put("action", "store_get")).optJSONObject("store")?.optJSONObject("value") ?: JSONObject()
        val raw = jsonObjectToMap(value)
        StoreOperation(open = value.optBoolean("aberta", false), pausedUntilMillis = parseIsoMillis(value.optString("pausaAte")),
            maintenance = value.optBoolean("manutencao"), emergency = value.optBoolean("emergencia"),
            closedMessage = value.optString("mensagemFechada"), demandMessage = value.optString("avisoDemanda"),
            prepMinutes = value.optInt("tempoPreparoMin", 25), raw = raw)
    }, onData, onError)

    fun openChat(orderId: String, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        Thread {
            try {
                val id = request(JSONObject().put("action", "chat_open").put("orderId", orderId)).getJSONObject("conversation").getString("id")
                mainHandler.post { onReady(id) }
            } catch (e: Throwable) { mainHandler.post { onError(e) } }
        }.start()
    }

    fun listenChat(id: String, onData: (OrderChat) -> Unit, onError: (Throwable) -> Unit): ListenerRegistration = poll({
        val rows = request(JSONObject().put("action", "chat_messages").put("conversationId", id)).getJSONArray("messages")
        val messages = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val millis = parseIsoMillis(row.optString("created_at"))
            ChatMessage(sender = if (row.optString("sender_type") == "staff") "gestor" else "cliente", text = row.optString("body"), time = timeText(millis), timestamp = millis)
        }
        OrderChat(id, "", messages)
    }, onData, onError)

    fun sendChat(id: String, text: String, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(JSONObject().put("action", "chat_send").put("conversationId", id).put("body", text.trim()), onDone, onError)

    private fun <T> poll(fetch: () -> T, onData: (T) -> Unit, onError: (Throwable) -> Unit): ListenerRegistration {
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.scheduleWithFixedDelay({
            try { val data = fetch(); mainHandler.post { if (!stopped.get()) onData(data) } }
            catch (e: Throwable) { mainHandler.post { if (!stopped.get()) onError(e) } }
        }, 0, 5, TimeUnit.SECONDS)
        return object : ListenerRegistration { override fun remove() { stopped.set(true); executor.shutdownNow() } }
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

    /**
     * gestor-orders hoje devolve as colunas normalizadas do Supabase, além de
     * raw_payload, customer, items e courier. Convertemos isso para o formato
     * interno legado do Gestor para preservar toda a tela de pedido sem depender
     * do antigo campo `raw`.
     */
    private fun fetchOrders(): List<Order> {
        val response = request(JSONObject().put("action", "list").put("limit", 120))
        val rows = response.optJSONArray("orders") ?: throw IllegalStateException("Resposta de pedidos inválida. Tentando novamente.")
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue

                val raw = (row.optJSONObject("raw_payload")?.let(::jsonObjectToMap)?.toMutableMap()
                    ?: linkedMapOf())

                row.optString("status").takeIf { it.isNotBlank() }?.let {
                    raw["status"] = it
                    raw["statusPedido"] = it
                }
                row.optString("order_code").takeIf { it.isNotBlank() }?.let { raw["numeroPedido"] = it }
                raw["total"] = row.optDoubleOrNull("total") ?: raw["total"]
                raw["subtotal"] = row.optDoubleOrNull("subtotal") ?: raw["subtotal"]
                raw["taxaEntrega"] = row.optDoubleOrNull("delivery_fee") ?: raw["taxaEntrega"]
                raw["desconto"] = row.optDoubleOrNull("discount") ?: raw["desconto"]
                raw["observacao"] = row.optString("customer_note").takeIf { it.isNotBlank() } ?: raw["observacao"]
                raw["tipoEntrega"] = row.optString("fulfillment_type").takeIf { it.isNotBlank() } ?: raw["tipoEntrega"]

                val createdMillis = parseIsoMillis(row.optString("created_at"))
                if (createdMillis > 0L) raw["createdAt"] = createdMillis

                row.optString("cancellation_reason").takeIf { it.isNotBlank() && it != "null" }?.let { raw["motivoCancelamento"] = it }
                row.optString("source").takeIf { it.isNotBlank() }?.let { raw["source"] = it }
                row.optJSONObject("courier")?.let { raw["entregadorNome"] = it.optString("name") }
                row.optJSONObject("delivery_address")?.let { raw["endereco"] = jsonObjectToMap(it) }

                val customer = row.optJSONObject("customer")
                if (customer != null) {
                    val client = linkedMapOf<String, Any?>()
                    client["nome"] = customer.optString("name")
                    client["telefone"] = customer.optString("phone")
                    client["email"] = customer.optString("email")
                    client["uid"] = customer.optString("id")
                    raw["cliente"] = client
                }

                val payment = row.optJSONObject("payment_details")?.let(::jsonObjectToMap)?.toMutableMap()
                    ?: linkedMapOf()
                row.optString("payment_method").takeIf { it.isNotBlank() }?.let { payment["forma"] = it }
                row.optString("payment_status").takeIf { it.isNotBlank() }?.let { payment["status"] = it }
                if (payment.isNotEmpty()) raw["pagamento"] = payment

                val items = mutableListOf<Map<String, Any?>>()
                val jsonItems = row.optJSONArray("items") ?: JSONArray()
                for (itemIndex in 0 until jsonItems.length()) {
                    val item = jsonItems.optJSONObject(itemIndex) ?: continue
                    val itemRaw = item.optJSONObject("raw_payload")?.let(::jsonObjectToMap)?.toMutableMap()
                        ?: linkedMapOf()
                    item.optString("name").takeIf { it.isNotBlank() }?.let {
                        itemRaw["nome"] = it
                        itemRaw["titulo"] = it
                    }
                    item.optDoubleOrNull("quantity")?.let { itemRaw["quantidade"] = it }
                    item.optDoubleOrNull("unit_price")?.let { itemRaw["preco"] = it }
                    item.optDoubleOrNull("total_price")?.let { itemRaw["total"] = it }
                    item.optString("notes").takeIf { it.isNotBlank() }?.let { itemRaw["observacao"] = it }
                    item.optJSONObject("modifiers")?.let { modifiers ->
                        val details = jsonObjectToMap(modifiers)
                        itemRaw["detalhes"] = details
                        if (!itemRaw.containsKey("linhas")) {
                            (details["linhas"] as? List<*>)?.let { itemRaw["linhas"] = it }
                        }
                    }
                    items += itemRaw
                }
                if (items.isNotEmpty()) raw["itens"] = items

                add(normalizeOrder(id, raw))
            }
        }.sortedByDescending { it.createdMillis }
    }

    internal fun request(payload: JSONObject): JSONObject {
        val pin = GestorCredentials.pin.trim()
        if (pin.length != 6) {
            throw IllegalStateException("PIN do Gestor não configurado. Feche e abra o aplicativo.")
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
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
                throw IllegalStateException("PIN do Gestor inválido. Feche e abra o aplicativo para digitar novamente.")
            }
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                val error = json.optString("error").ifBlank { "Falha ao acessar pedidos do Supabase ($code)." }
                throw IllegalStateException(error)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(',', '.').toDoubleOrNull()
            else -> null
        }
    }

    private fun parseIsoMillis(value: String): Long {
        if (value.isBlank()) return 0L
        val normalized = value.trim()
            .replace(Regex("(\\.\\d{3})\\d+"), "$1")
            .replace(" ", "T")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                return parser.parse(normalized)?.time ?: continue
            } catch (_: Throwable) {
            }
        }
        return 0L
    }

    internal fun jsonObjectToMap(value: JSONObject): Map<String, Any?> {
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
