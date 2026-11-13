package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class PaymentStatusDisplayTest {
    @Test fun canonicalPaymentStatusOverridesStalePayload() {
        val order = normalizeOrder("id123", mapOf(
            "numeroPedido" to "1042",
            "status" to "CONFIRMADO",
            "payment_status" to "PAGO",
            "pagamento" to mapOf("status" to "PENDENTE", "forma" to "PIX"),
            "itens" to emptyList<Map<String,Any>>(),
            "total" to 24.0
        ))
        assertEquals("PAGO", order.payment.status)
        assertEquals("Pago no sistema", PaymentStatusDisplay.label(order.payment.status))
    }

    @Test fun neverTreatUnconfirmedOrReversedPaymentsAsPaid() {
        for (s in listOf("", "PENDENTE", "AGUARDANDO_PAGAMENTO",
            "RECUSADO", "REEMBOLSADO", "ESTORNADO", "ERRO", "PROCESSANDO")) {
            assertFalse("unexpected paid: " + s, PaymentStatusDisplay.isPaid(s))
        }
    }

    @Test fun recognizesCanonicalPaidStatuses() {
        for (s in listOf("PAGO", "paid", "CONFIRMADO", "APROVADO", "approved")) {
            assertTrue("unexpected unpaid: " + s, PaymentStatusDisplay.isPaid(s))
        }
    }

    @Test fun unknownStatusIsNotPresentedAsPaid() {
        assertTrue(PaymentStatusDisplay.label("EM_ANALISE").contains("verificação"))
        assertFalse(PaymentStatusDisplay.isPaid("EM_ANALISE"))
    }
}
