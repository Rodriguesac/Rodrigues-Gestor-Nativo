package com.rodrigues.gestor.data

import android.content.Context
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject

/**
 * Operações do gestor via gestor-app-api: autenticação e permissões
 * validadas no servidor. Nunca confirma alterações apenas localmente.
 */
data class StoreInfo(
    val open: Boolean = false,
    val prepMinutes: Int = 25,
    val message: String = "",
    val updatedAt: String = "",
    val loaded: Boolean = false
)

data class CourierChoice(
    val id: String,
    val name: String,
    val phone: String,
    val online: Boolean,
    val status: String
) {
    val available: Boolean get() = online && status.lowercase() in
        setOf("available", "disponivel", "disponível", "livre", "online", "ativo", "active")
}

object NativeOperationsApi {
    private const val STORE_PREFS = "gestor20_store_snapshot_v1"
    private val gson = Gson()

    fun cachedStore(context: Context): StoreInfo {
        return try {
            val json = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)
                .getString("last", null) ?: return StoreInfo()
            gson.fromJson(json, StoreInfo::class.java) ?: StoreInfo()
        } catch (_: Throwable) { StoreInfo() }
    }

    private fun persist(context: Context, value: StoreInfo) {
        context.applicationContext.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE)
            .edit().putString("last", gson.toJson(value)).apply()
    }

    private fun parseStore(root: JSONObject): StoreInfo {
        val value = root.optJSONObject("operation")?.optJSONObject("value") ?: JSONObject()
        return StoreInfo(
            open = value.optBoolean("aberta", value.optBoolean("lojaAberta", false)),
            prepMinutes = value.optInt("tempoEstimadoMin", 25).coerceIn(1, 180),
            message = value.optString("mensagemOperacao"),
            updatedAt = value.optString("updatedAt", value.optString("atualizadoEm")),
            loaded = true
        )
    }

    fun loadStore(context: Context, onSuccess: (StoreInfo) -> Unit, onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("store", onSuccess = {
            val state = parseStore(it)
            persist(context, state)
            onSuccess(state)
        }, onError = onError)
    }

    fun setOpen(context: Context, open: Boolean, onSuccess: (StoreInfo) -> Unit,
                onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("set_operation", JSONObject().put("open", open),
            onSuccess = { loadStore(context, onSuccess, onError) }, onError = onError)
    }

    fun saveSettings(context: Context, prep: Int, message: String,
                     onSuccess: (StoreInfo) -> Unit, onError: (Throwable) -> Unit) {
        if (prep !in 1..180 || message.length > 500) {
            onError(IllegalArgumentException("Prazo entre 1 e 180 minutos; aviso até 500 caracteres."))
            return
        }
        NativeCatalogApi.execute("operation_save",
            JSONObject().put("preparation_minutes", prep).put("message", message),
            onSuccess = { loadStore(context, onSuccess, onError) }, onError = onError)
    }

    fun dispatchOwn(orderId: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("up_dispatch_without_courier",
            JSONObject().put("order_id", orderId), onSuccess = { onSuccess() }, onError = onError)
    }

    fun couriers(onSuccess: (List<CourierChoice>) -> Unit, onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("couriers", onSuccess = { response ->
            val rows = response.optJSONArray("couriers") ?: JSONArray()
            val list = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    if (id.isBlank()) continue
                    add(CourierChoice(id, row.optString("name", "Entregador"),
                        row.optString("phone"), row.optBoolean("online", false),
                        row.optString("status")))
                }
            }
            onSuccess(list.sortedWith(compareByDescending<CourierChoice> { it.available }.thenBy { it.name }))
        }, onError = onError)
    }

    /**
     * Recolhe apenas oferta que ainda aguarda aceite.
     * A função UP do servidor também valida status e atualização concorrente.
     */
    fun recallUpOffer(orderId: String, onSuccess: () -> Unit,
                      onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("up_recall_offer",
            JSONObject().put("order_id", orderId),
            onSuccess = { onSuccess() }, onError = onError)
    }

    fun offerUp(orderId: String, courierId: String,
                onSuccess: (JSONObject) -> Unit, onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("up_offer_to_courier",
            JSONObject().put("order_id", orderId).put("courier_id", courierId),
            onSuccess = onSuccess, onError = onError)
    }

    /**
     * Avança status pela API canônica do Gestor20.
     * O servidor impõe transições e impede avanço direto de corrida UP.
     */
    fun updateOrderStatus(orderId: String, status: String, onSuccess: () -> Unit,
                          onError: (Throwable) -> Unit) {
        if (status !in setOf("CONFIRMADO", "EM_PREPARO", "PRONTO", "CONCLUIDO")) {
            onError(IllegalArgumentException("Transição não permitida pelo aplicativo."))
            return
        }
        NativeCatalogApi.execute("update_order",
            JSONObject().put("order_id", orderId)
                .put("patch", JSONObject().put("status", status)),
            onSuccess = { onSuccess() }, onError = onError)
    }

    /** O backend valida status, motivo e bloqueia cancelamento com missão UP ativa. */
    fun cancelOrder(orderId: String, reason: String, onSuccess: () -> Unit,
                    onError: (Throwable) -> Unit) {
        val clean = reason.trim()
        if (clean.length < 3 || clean.length > 500) {
            onError(IllegalArgumentException("Informe o motivo do cancelamento (3 a 500 caracteres)."))
            return
        }
        NativeCatalogApi.execute("update_order",
            JSONObject().put("order_id", orderId).put("patch",
                JSONObject().put("status", "CANCELADO").put("cancellation_reason", clean)),
            onSuccess = { onSuccess() }, onError = onError)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
