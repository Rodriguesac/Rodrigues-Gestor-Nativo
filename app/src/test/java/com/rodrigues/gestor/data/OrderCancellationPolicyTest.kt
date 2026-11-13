package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class OrderCancellationPolicyTest {
    private fun order(status: String = "PRONTO", raw: Map<String, Any?> = emptyMap()) = Order(
        id="4b14f616-7777-4777-8777-123456789abc", number="10", status=status,
        clientName="Teste", clientUid="", phone="", address="Rua",
        neighborhood="", items=emptyList(), observation="", total=10.0,
        subtotal=10.0, freight=0.0, discount=0.0, createdMillis=1,
        payment=PaymentInfo("DINHEIRO", "PENDENTE"), pickup=false, raw=raw
    )

    @Test fun normalOrderUsesGenericCancellation() {
        val simple=order()
        assertFalse(OrderCancellationPolicy.needsUpFlow(simple))
        assertNull(OrderCancellationPolicy.validate(simple,"sem estoque"))
    }

    @Test fun upMissionMustUseDedicatedEndpoint() {
        val active=order("A_CAMINHO_LOJA", mapOf(
            "courier_id" to "courier", "up_state" to "TO_STORE"))
        assertTrue(OrderCancellationPolicy.needsUpFlow(active))
        assertEquals(5,OrderCancellationPolicy.minReasonLength(active))
        assertNotNull(OrderCancellationPolicy.validate(active,"no"))
        assertNull(OrderCancellationPolicy.validate(active,"Cliente solicitou cancelamento"))
    }

    @Test fun pendingOfferAlsoUsesUpCancellationWhenCancellingEntireOrder() {
        val offer=order("AGUARDANDO_ENTREGADOR", mapOf(
            "up_target_courier_id" to "driver", "up_state" to "OFFER_PENDING"))
        assertTrue(OrderCancellationPolicy.needsUpFlow(offer))
    }

    @Test fun closedOrderCannotBeCancelledAgain() {
        assertNotNull(OrderCancellationPolicy.validate(order("CANCELADO"),"repetido"))
        assertNotNull(OrderCancellationPolicy.validate(order("ENTREGUE"),"repetido"))
    }
}
