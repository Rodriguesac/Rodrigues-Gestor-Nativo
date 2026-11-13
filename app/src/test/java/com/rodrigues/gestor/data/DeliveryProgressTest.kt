package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class DeliveryProgressTest {
    private fun delivery(status: String = "PRONTO",
                         raw: Map<String, Any?> = emptyMap(), pickup: Boolean = false) = Order(
        id = "0a4d6f5e-7777-4777-8777-123456789abc",
        number = "1042", status = status, clientName = "Teste", clientUid = "",
        phone = "", address = "Rua Teste 1", neighborhood = "Centro",
        items = emptyList(), observation = "", total = 15.0, subtotal = 15.0,
        freight = 0.0, discount = 0.0, createdMillis = 1,
        payment = PaymentInfo("DINHEIRO", "PENDENTE"), pickup = pickup, raw = raw
    )

    @Test fun pendingOfferNeverClaimedAsAccepted() {
        val progress = DeliveryProgress.from(delivery(
            status = "AGUARDANDO_ENTREGADOR",
            raw = mapOf("up_state" to "OFFER_PENDING",
                "up_target_courier_id" to "a1b2c3")
        ))
        assertTrue(progress.hasUpMission)
        assertTrue(progress.canRecallOffer)
        assertEquals("UP • aguardando aceite", progress.label)
        assertFalse(progress.isOwn)
        assertTrue(progress.detail.contains("não houve aceite"))
    }

    @Test fun cannotRecallAfterCourierAccepts() {
        val progress = DeliveryProgress.from(delivery(
            status = "A_CAMINHO_LOJA", raw = mapOf("up_state" to "TO_STORE",
                "courier_id" to "assigned", "up_target_courier_id" to "assigned")
        ))
        assertTrue(progress.hasUpMission)
        assertFalse(progress.canRecallOffer)
        assertTrue(progress.label.contains("loja"))
    }

    @Test fun ownDeliveryIsNotUp() {
        val progress = DeliveryProgress.from(delivery(
            status = "EM_ENTREGA",
            raw = mapOf("delivery_dispatch_mode" to "STORE", "up_state" to "MANUAL_DISPATCH")
        ))
        assertTrue(progress.isOwn)
        assertFalse(progress.isUp)
        assertFalse(progress.canRecallOffer)
    }

    @Test fun conflictingUpMissionOverridesOwnFlag() {
        val progress = DeliveryProgress.from(delivery(
            status = "EM_ENTREGA",
            raw = mapOf("delivery_dispatch_mode" to "STORE", "courier_id" to "driver")
        ))
        assertTrue(progress.isUp)
        assertFalse(progress.isOwn)
    }

    @Test fun noFakeTrackingOrCourierIdForUnassignedOrder() {
        val progress = DeliveryProgress.from(delivery())
        assertFalse(progress.hasUpMission)
        assertFalse(progress.isUp)
    }

    @Test fun pickupIsNeverMarkedAsUpMission() {
        val progress = DeliveryProgress.from(delivery(
            pickup = true,
            raw = mapOf("up_state" to "OFFER_PENDING",
                "up_target_courier_id" to "driver")
        ))
        assertFalse(progress.isUp)
        assertEquals("Retirada na loja", progress.label)
    }
}
