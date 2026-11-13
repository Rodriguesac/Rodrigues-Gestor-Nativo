package com.rodrigues.gestor.data

import java.util.Locale

/**
 * Uma única política de botões locais. O servidor é a autoridade e valida
 * a transição novamente antes de alterar o pedido.
 */
object OrderFlowPolicy {
    private fun status(order: Order): String = order.status.uppercase(Locale.ROOT)

    fun nextAction(order: Order): String? {
        val state = status(order)
        return when {
            state in StatusGroups.NEW -> "CONFIRMADO"
            state in StatusGroups.CONFIRMED -> "EM_PREPARO"
            state in StatusGroups.PREPARING -> "PRONTO"
            state in StatusGroups.READY && order.pickup -> "RETIRADO"
            state == "EM_ENTREGA" &&
                DeliveryProgress.from(order).isOwn &&
                !DeliveryProgress.from(order).hasUpMission -> "CONCLUIDO"
            else -> null
        }
    }

    fun canDispatchOwn(order: Order): Boolean =
        !order.pickup && !DeliveryProgress.from(order).hasUpMission &&
        status(order) in setOf("PRONTO", "AGUARDANDO_DECISAO_GESTOR", "BUSCANDO_ENTREGADOR")

    fun canOfferUp(order: Order): Boolean =
        !order.pickup && !DeliveryProgress.from(order).hasUpMission &&
        status(order) in setOf("PRONTO", "AGUARDANDO_DECISAO_GESTOR",
            "BUSCANDO_ENTREGADOR", "AGUARDANDO_ENTREGADOR")

    fun canCancel(order: Order): Boolean =
        status(order) !in StatusGroups.DONE &&
        status(order) !in StatusGroups.CANCELED &&
        status(order) != "CANCELAMENTO_EM_ANALISE"
}
