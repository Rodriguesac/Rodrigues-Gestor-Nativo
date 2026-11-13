package com.rodrigues.gestor.data

/**
 * Missão UP exige cancelamento via up_cancel_delivery, nunca update_order genérico.
 * O servidor é a fonte de verdade e decide se é necessário suporte/devolução.
 */
object OrderCancellationPolicy {
    fun needsUpFlow(order: Order): Boolean = DeliveryProgress.from(order).hasUpMission
    fun minReasonLength(order: Order): Int = if (needsUpFlow(order)) 5 else 3
    fun validate(order: Order, reason: String): String? {
        val trimmed = reason.trim()
        if (!OrderFlowPolicy.canCancel(order)) return "Pedido já encerrado."
        if (trimmed.length !in minReasonLength(order)..500) {
            return "Informe o motivo do cancelamento (" +
                minReasonLength(order) + " a 500 caracteres)."
        }
        return null
    }
}
