package com.rodrigues.gestor.data

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SupabaseOrdersApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-app-api"
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
            .put("action", "update_order")
            .put("order_id", orderId)
            .put("patch", JSONObject().put("status", status)),
        onDone,
        onError,
    )

    fun finishPickup(orderId: String, onDone: () -> Unit, onError: (Throwable) -> Unit) =
        runAction(
            JSONObject().put("action", "update_order").put("order_id", orderId).put("patch", JSONObject().put("status", "CONCLUIDO")),
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
            .put("action", "update_order")
            .put("order_id", orderId)
            .put("patch", JSONObject().put("status", "CANCELADO").put("cancellation_reason", reason)),
        onDone,
        onError,
    )

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

    private fun fetchOrders(): List<Order> {
        val response = request(JSONObject().put("action", "orders").put("include_recent", true))
        val rows = response.optJSONArray("orders") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (id.isBlank()) continue

                // A tabela orders é a fonte canônica. raw_payload guarda a forma original
                // do pedido, mas status/valores podem ter mudado depois.
                val raw = (row.optJSONObject("raw_payload") ?: row.optJSONObject("raw") ?: JSONObject()).let { original ->
                    JSONObject(original.toString())
                }
                raw.put("status", row.optString("status", raw.optString("status", "PENDENTE")))
                raw.put("codigoPedido", row.optString("order_code", raw.optString("codigoPedido")))
                raw.put("numeroPedido", row.optString("order_code", raw.optString("numeroPedido")))
                raw.put("tipoPedido", row.optString("fulfillment_type", raw.optString("tipoPedido")))
                raw.put("subtotal", row.optDouble("subtotal", raw.optDouble("subtotal", 0.0)))
                raw.put("frete", row.optDouble("delivery_fee", raw.optDouble("frete", 0.0)))
                raw.put("desconto", row.optDouble("discount", raw.optDouble("desconto", 0.0)))
                raw.put("total", row.optDouble("total", raw.optDouble("total", 0.0)))
                raw.put("createdAt", row.optString("created_at", raw.optString("createdAt")))

                row.optJSONObject("delivery_address")?.let { raw.put("endereco", it) }
                row.optJSONObject("customer")?.let { customer ->
                    raw.put("cliente", JSONObject().apply {
                        put("nome", customer.optString("name", customer.optString("nome")))
                        put("telefone", customer.optString("phone", customer.optString("telefone")))
                        put("email", customer.optString("email"))
                        put("uid", customer.optString("auth_user_id", customer.optString("uid")))
                    })
                }
                row.optJSONObject("payment_details")?.let { raw.put("pagamento", it) }

                val enrichedItems = row.optJSONArray("items")
                if (enrichedItems != null && enrichedItems.length() > 0) {
                    val normalizedItems = JSONArray()
                    for (itemIndex in 0 until enrichedItems.length()) {
                        val item = enrichedItems.optJSONObject(itemIndex) ?: continue
                        normalizedItems.put(JSONObject().apply {
                            put("nome", item.optString("name", "Item"))
                            put("quantidade", item.optDouble("quantity", 1.0))
                            put("total", item.optDouble("total_price", item.optDouble("unit_price", 0.0)))
                            put("detalhes", item.optJSONObject("modifiers") ?: JSONObject())
                            put("linhasMontagem", (item.optJSONObject("modifiers") ?: JSONObject()).optJSONArray("linhasMontagem")
                                ?: (item.optJSONObject("modifiers") ?: JSONObject()).optJSONArray("linhas")
                                ?: JSONArray())
                        })
                    }
                    raw.put("itens", normalizedItems)
                }

                add(normalizeOrder(id, jsonObjectToMap(raw)))
            }
        }.sortedByDescending { it.createdMillis }
    }

    private fun request(payload: JSONObject): JSONObject {
        val token = GestorCredentials.session.trim()
        if (token.isBlank()) {
            throw IllegalStateException("Entre no Gestor para receber os pedidos.")
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", "https://appassets.androidplatform.net")
        }

        payload.put("session_token", token)
        try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)

            if (code == 401) {
                
                throw IllegalStateException("Acesso expirou. Entre novamente no Gestor.")
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

