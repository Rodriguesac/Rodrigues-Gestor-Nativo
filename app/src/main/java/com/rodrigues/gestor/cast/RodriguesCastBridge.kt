package com.rodrigues.gestor.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.rodrigues.gestor.data.Order
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object RodriguesCastBridge {
    fun isConnected(context: Context): Boolean =
        try {
            CastContext.getSharedInstance(context).sessionManager.currentCastSession?.isConnected == true
        } catch (_: Throwable) {
            false
        }

    fun deviceName(context: Context): String? =
        try {
            CastContext.getSharedInstance(context)
                .sessionManager
                .currentCastSession
                ?.castDevice
                ?.friendlyName
        } catch (_: Throwable) {
            null
        }

    fun sendOrders(context: Context, orders: List<Order>): Boolean {
        val session = try {
            CastContext.getSharedInstance(context).sessionManager.currentCastSession
        } catch (_: Throwable) {
            null
        } ?: return false

        if (!session.isConnected) return false

        val payload = JSONObject()
            .put("type", "orders_snapshot")
            .put("sentAt", System.currentTimeMillis())
            .put("orders", JSONArray().apply {
                orders
                    .filter { isKitchenActive(it.status) }
                    .sortedBy { if (it.createdMillis > 0L) it.createdMillis else Long.MAX_VALUE }
                    .forEach { put(orderJson(it)) }
            })

        return try {
            session.sendMessage(RodriguesCastConfig.NAMESPACE, payload.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun sendAttention(context: Context, order: Order): Boolean {
        val session = try {
            CastContext.getSharedInstance(context).sessionManager.currentCastSession
        } catch (_: Throwable) {
            null
        } ?: return false
        if (!session.isConnected) return false

        val payload = JSONObject()
            .put("type", "attention")
            .put("orderId", order.id)
            .put("number", order.number)
            .put("clientName", order.clientName)

        return try {
            session.sendMessage(RodriguesCastConfig.NAMESPACE, payload.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun orderJson(order: Order): JSONObject =
        JSONObject()
            .put("id", order.id)
            .put("number", order.number)
            .put("status", order.status)
            .put("clientName", order.clientName)
            .put("address", order.address)
            .put("neighborhood", order.neighborhood)
            .put("pickup", order.pickup)
            .put("createdMillis", order.createdMillis)
            .put("observation", order.observation)
            .put("total", order.total)
            .put("items", JSONArray().apply {
                order.items.forEach { item ->
                    put(
                        JSONObject()
                            .put("name", item.name)
                            .put("quantity", item.quantity)
                            .put("price", item.price)
                            .put("details", JSONArray(sortAcaiDetails(item.details)))
                    )
                }
            })

    private fun isKitchenActive(status: String): Boolean {
        val s = status.uppercase(Locale.ROOT)
        return s in setOf(
            "AGUARDANDO_CONFIRMACAO", "RECEBIDO", "PENDENTE", "NOVO", "NOVO_PEDIDO",
            "CONFIRMADO", "FILA", "ACEITO",
            "EM_PREPARO", "PREPARANDO",
            "PRONTO"
        )
    }

    private fun sortAcaiDetails(details: List<String>): List<String> {
        val fruits = listOf(
            "morango", "kiwi", "banana", "abacaxi", "manga", "uva", "maca", "maçã",
            "melancia", "melao", "melão", "maracuja", "maracujá", "pessego", "pêssego"
        )
        fun priority(value: String): Int {
            val s = value.lowercase(Locale("pt", "BR"))
            return when {
                "leite condensado" in s -> 0
                "leite em pó" in s || "leite em po" in s -> 1
                "cobertura" in s || "calda" in s -> 2
                fruits.any { it in s } -> 9
                else -> 4
            }
        }
        return details.withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { priority(it.value) }.thenBy { it.index })
            .map { it.value }
    }
}
