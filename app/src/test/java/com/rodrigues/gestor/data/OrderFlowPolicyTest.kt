package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class OrderFlowPolicyTest {
    private fun order(status: String, pickup: Boolean = false,
        raw: Map<String, Any?> = emptyMap()) = Order(
        id = "8ef41916-e55a-46bb-8c9a-caa28cc37dfc",
        number = "192", status = status,
        clientName = "Cliente", clientUid = "", phone = "",
        address = "Rua A", neighborhood = "Centro", items = emptyList(),
        observation = "", total = 19.0, subtotal = 19.0,
        freight = 0.0, discount = 0.0, createdMillis = 1,
        payment = PaymentInfo("PIX","PENDENTE"), pickup = pickup, raw = raw
    )

    @Test fun progressionNewPreparationReady() {
        assertEquals("CONFIRMADO", OrderFlowPolicy.nextAction(order("NOVO")))
        assertEquals("EM_PREPARO", OrderFlowPolicy.nextAction(order("CONFIRMADO")))
        assertEquals("PRONTO", OrderFlowPolicy.nextAction(order("EM_PREPARO")))
    }

    @Test fun pickupCannotOfferCourier() {
        val o = order("PRONTO", pickup = true)
        assertEquals("RETIRADO", OrderFlowPolicy.nextAction(o))
        assertFalse(OrderFlowPolicy.canOfferUp(o))
        assertFalse(OrderFlowPolicy.canDispatchOwn(o))
    }

    @Test fun deliveryReadyMustChooseMode() {
        val o = order("PRONTO")
        assertNull(OrderFlowPolicy.nextAction(o))
        assertTrue(OrderFlowPolicy.canOfferUp(o))
        assertTrue(OrderFlowPolicy.canDispatchOwn(o))
    }

    @Test fun upPendingPreventsOtherDispatch() {
        val o = order("AGUARDANDO_ENTREGADOR",
            raw = mapOf("up_state" to "OFFER_PENDING",
                "up_target_courier_id" to "courier"))
        assertFalse(OrderFlowPolicy.canDispatchOwn(o))
        assertFalse(OrderFlowPolicy.canOfferUp(o))
        assertNull(OrderFlowPolicy.nextAction(o))
    }

    @Test fun acceptedUpMustNeverBeFinalizedManually() {
        val o = order("EM_ENTREGA",
            raw = mapOf("courier_id" to "courier", "up_state" to "TO_CUSTOMER"))
        assertNull(OrderFlowPolicy.nextAction(o))
    }

    @Test fun onlyOwnDeliveryCanBeFinalizedByManager() {
        val own = order("EM_ENTREGA", raw = mapOf("delivery_dispatch_mode" to "STORE"))
        assertEquals("CONCLUIDO", OrderFlowPolicy.nextAction(own))
        assertNull(OrderFlowPolicy.nextAction(order("EM_ENTREGA")))
    }

    @Test fun closedOrdersRejectChanges() {
        for (state in listOf("ENTREGUE", "CANCELADO", "RETIRADO")) {
            val o = order(state)
            assertNull(OrderFlowPolicy.nextAction(o))
            assertFalse(OrderFlowPolicy.canCancel(o))
            assertFalse(OrderFlowPolicy.canOfferUp(o))
            assertFalse(OrderFlowPolicy.canDispatchOwn(o))
        }
    }
}
