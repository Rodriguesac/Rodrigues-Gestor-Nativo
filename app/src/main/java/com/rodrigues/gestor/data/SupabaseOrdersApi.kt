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
                val raw = row.optJSONObject("raw") ?: JSONObject()
                if (id.isBlank()) continue
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
