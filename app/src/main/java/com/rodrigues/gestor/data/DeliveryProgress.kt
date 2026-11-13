package com.rodrigues.gestor.data

import java.util.Locale

/**
 * Status consolidado da entrega: lê apenas campos existentes na cópia canônica.
 * Nunca apresenta a oferta como aceita, nem inventa posição de motoboy.
 */
data class DeliveryProgress(
    val label: String,
    val detail: String,
    val isUp: Boolean,
    val isOwn: Boolean,
    val hasUpMission: Boolean,
    val canRecallOffer: Boolean
) {
    companion object {
        private fun normalized(v: Any?): String =
            v?.toString()?.trim()?.uppercase(Locale.ROOT)
                ?.takeUnless { it == "NULL" || it == "NONE" || it == "UNDEFINED" }
                .orEmpty()

        fun from(order: Order): DeliveryProgress {
            if (order.pickup) return DeliveryProgress(
                "Retirada na loja", "Não é uma entrega por motoboy.", false, false, false, false
            )
            val status = normalized(order.status)
            val raw = order.raw
            val upState = normalized(raw["up_state"])
            val courierId = normalized(raw["courier_id"])
            val targetId = normalized(raw["up_target_courier_id"])
            val pickupAt = normalized(raw["up_pickup_at"])
            val ownDispatch = normalized(raw["delivery_dispatch_mode"]) == "STORE"
            val knownUpStates = setOf(
                "OFFER_PENDING", "OFFER_ACCEPTED", "ACCEPTED", "TO_STORE",
                "AT_STORE", "TO_CUSTOMER", "AT_CUSTOMER", "PICKED_UP"
            )
            val activeUp = courierId.isNotBlank() || targetId.isNotBlank() ||
                pickupAt.isNotBlank() || upState in knownUpStates
            val recall = courierId.isBlank() && targetId.isNotBlank() &&
                upState == "OFFER_PENDING" && status == "AGUARDANDO_ENTREGADOR"

            if (ownDispatch && !activeUp) return DeliveryProgress(
                "Entrega própria", when (status) {
                    "EM_ENTREGA", "SAIU_ENTREGA", "SAIU_PARA_ENTREGA" ->
                        "A loja despachou o pedido. A confirmação da entrega é feita pelo gestor."
                    "ENTREGUE", "CONCLUIDO" -> "Entrega finalizada pela loja."
                    else -> "Entrega administrada diretamente pela loja."
                }, false, true, false, false
            )
            if (activeUp) {
                val label = when {
                    status in StatusGroups.DONE -> "UP • entrega concluída"
                    status in StatusGroups.CANCELED -> "UP • entrega cancelada"
                    recall -> "UP • aguardando aceite"
                    upState == "OFFER_RECALLED" -> "UP • oferta recolhida"
                    upState in setOf("OFFER_ACCEPTED", "ACCEPTED") -> "UP • aceita"
                    upState in setOf("TO_STORE") ||
                        status in setOf("A_CAMINHO_LOJA", "ENTREGADOR_A_CAMINHO_LOJA") ->
                        "UP • a caminho da loja"
                    upState in setOf("AT_STORE") ||
                        status in setOf("COLETANDO", "ENTREGADOR_CHEGOU_LOJA") ->
                        "UP • na loja"
                    upState in setOf("TO_CUSTOMER", "PICKED_UP") ||
                        status in setOf("A_CAMINHO_CLIENTE", "SAIU_ENTREGA", "EM_ENTREGA") ->
                        "UP • em rota ao cliente"
                    upState == "AT_CUSTOMER" || status == "ENTREGADOR_NO_LOCAL" ->
                        "UP • no endereço do cliente"
                    else -> "UP • missão em andamento"
                }
                val detail = when {
                    recall -> "Oferta enviada ao entregador. Ainda não houve aceite."
                    status in StatusGroups.DONE -> "Entrega finalizada, conforme status do servidor."
                    else -> "Acompanhe as atualizações da missão enviadas pelo UP Entregas."
                }
                return DeliveryProgress(label, detail, true, false, true, recall)
            }
            if (upState == "OFFER_RECALLED") return DeliveryProgress(
                "UP • oferta recolhida",
                "A oferta anterior foi recolhida. Escolha outra forma de despacho.",
                false, false, false, false
            )
            return DeliveryProgress(
                StatusGroups.label(status),
                "Nenhuma oferta UP ou despacho próprio ativo neste pedido.",
                false, false, false, false
            )
        }
    }
}
