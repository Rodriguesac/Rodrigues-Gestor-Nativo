package com.rodrigues.gestor.data

import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object SupabaseOrdersApi {
    private const val ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-orders"
    private const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impnam1udGV6Zmp1eXV4aGNudmhkIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc1MzgyOTksImV4cCI6MjEwMzExNDI5OX0.n3yg9IdC6j8OB-vwi-yuXRKJZE3jdlL_wj3qVr-JBBc"
    private const val REALTIME_URL = "wss://jgjmntezfjuyuxhcnvhd.supabase.co/realtime/v1/websocket?apikey=" + ANON_KEY + "&vsn=1.0.0"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val realtimeClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
        intervalMs: Long = 3_000L,
    ): ListenerRegistration {
        val stopped = AtomicBoolean(false)
        val fetching = AtomicBoolean(false)
        val reference = AtomicInteger(0)
        var socket: WebSocket? = null

        fun refresh() {
            if (stopped.get() || !fetching.compareAndSet(false, true)) return
            Thread {
                try {
                    val rows = fetchOrders()
                    mainHandler.post { if (!stopped.get()) onData(rows) }
                } catch (error: Throwable) {
                    mainHandler.post { if (!stopped.get()) onError(error) }
                } finally {
                    fetching.set(false)
                }
            }.start()
        }

        val refreshSignal = Runnable { refresh() }
        val heartbeat = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val ref = reference.incrementAndGet().toString()
                socket?.send(JSONObject().apply {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", JSONObject())
                    put("ref", ref)
                }.toString())
                mainHandler.postDelayed(this, 25_000L)
            }
        }

        fun connect() {
            if (stopped.get()) return
            val request = Request.Builder().url(REALTIME_URL).build()
            socket = realtimeClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ref = reference.incrementAndGet().toString()
                    val payload = JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("broadcast", JSONObject().apply { put("ack", false); put("self", false) })
                            put("presence", JSONObject().apply { put("key", "") })
                            put("postgres_changes", JSONArray().put(JSONObject().apply {
                                put("event", "*")
                                put("schema", "public")
                                put("table", "gestor_realtime_signal")
                            }))
                        })
                        put("access_token", ANON_KEY)
                    }
                    webSocket.send(JSONObject().apply {
                        put("topic", "realtime:gestor-native")
                        put("event", "phx_join")
                        put("payload", payload)
                        put("ref", ref)
                    }.toString())
                    mainHandler.removeCallbacks(heartbeat)
                    mainHandler.postDelayed(heartbeat, 25_000L)
                    refresh()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        if (message.optString("event") != "postgres_changes") return
                        val payload = message.optJSONObject("payload")
                        val data = payload?.optJSONObject("data")
                        val record = data?.optJSONObject("record")
                            ?: data?.optJSONObject("new")
                            ?: payload?.optJSONObject("record")
                        val id = record?.optString("id").orEmpty()
                        if (id.isBlank() || id == "orders") {
                            mainHandler.removeCallbacks(refreshSignal)
                            mainHandler.postDelayed(refreshSignal, 180L)
                        }
                    } catch (_: Throwable) { }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (stopped.get()) return
                    mainHandler.post { onError(t) }
                    mainHandler.removeCallbacks(heartbeat)
                    mainHandler.postDelayed({ if (!stopped.get()) connect() }, 3_500L)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (stopped.get()) return
                    mainHandler.removeCallbacks(heartbeat)
                    mainHandler.postDelayed({ if (!stopped.get()) connect() }, 3_500L)
                }
            })
        }

        connect()

        return object : ListenerRegistration {
            override fun remove() {
                stopped.set(true)
                mainHandler.removeCallbacks(refreshSignal)
                mainHandler.removeCallbacks(heartbeat)
                try { socket?.close(1000, "stop") } catch (_: Throwable) { }
                socket = null
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
        val response = request(JSONObject().put("action", "list").put("limit", 120))
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
