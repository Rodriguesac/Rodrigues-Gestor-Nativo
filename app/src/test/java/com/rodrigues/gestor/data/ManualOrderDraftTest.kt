package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ManualOrderDraftTest {
    @Test fun receiptRequiresServerProofBeforeClearingDraft() {
        val existing = ManualOrderReceipt.validated(
            "33333333-4444-4555-8666-777777777777", "9100001", true)
        assertEquals("9100001", existing.orderCode)
        assertTrue(existing.alreadyExists)
        assertThrows(IllegalArgumentException::class.java) {
            ManualOrderReceipt.validated("", "9100002", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualOrderReceipt.validated(
                "33333333-4444-4555-8666-777777777777", "", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualOrderReceipt.validated(
                "33333333-4444-4555-8666-777777777777", "ABC42", false)
        }
    }

    private fun valid() = ManualOrderDraft(
        customerName = "Cliente de teste",
        phone = "67999998888",
        items = listOf(
            ManualOrderItem(name = "Açaí 500 ml", quantity = "2", unitPrice = "14,50")
        )
    )

    @Test fun correctCustomerCanSubmit() {
        assertNull(valid().validate())
        assertEquals(BigDecimal("29.00"), valid().subtotal)
    }

    @Test fun deliveryMustHaveAddress() {
        val missing = valid().copy(fulfillment = "ENTREGA")
        assertNotNull(missing.validate())
        val ok = missing.copy(street = "Rua de teste", number = "20",
            neighborhood = "Bairro de teste", deliveryFee = "5,50")
        assertNull(ok.validate())
        assertEquals(BigDecimal("34.50"), ok.total)
    }

    @Test fun pickupDoesNotChargeDeliveryFee() {
        val draft = valid().copy(deliveryFee = "9,00")
        assertNull(draft.validate())
        assertEquals(BigDecimal("29.00"), draft.total)
    }

    @Test fun rejectsBadPrices() {
        assertNull(ManualOrderDraft.price("-1"))
        assertNull(ManualOrderDraft.price("10,999"))
        assertNull(ManualOrderDraft.price("1e10"))
        assertNull(ManualOrderDraft.price("10000,01"))
        assertEquals(BigDecimal("0.00"), ManualOrderDraft.price("0"))
        assertEquals(BigDecimal("9.90"), ManualOrderDraft.price("9,90"))
    }

    @Test fun rejectsInvalidQuantitiesAndPhone() {
        assertNotNull(valid().copy(phone = "123").validate())
        assertNotNull(valid().copy(items = listOf(
            ManualOrderItem(name = "Produto", quantity = "0", unitPrice = "1,00"))).validate())
        assertNotNull(valid().copy(items = listOf(
            ManualOrderItem(name = "Produto", quantity = "100", unitPrice = "1,00"))).validate())
        assertNotNull(valid().copy(items = emptyList()).validate())
        assertNotNull(valid().copy(items = List(21) {
            ManualOrderItem(name = "Produto", quantity = "1", unitPrice = "1,00")
        }).validate())
    }

    @Test fun rejectsMissingNameAndAddress() {
        assertNotNull(valid().copy(customerName = "").validate())
        assertNotNull(valid().copy(items = listOf(
            ManualOrderItem(name = "", quantity = "1", unitPrice = "2,00"))).validate())
    }

    @Test fun retryKeepsSameSubmissionId() {
        val first = valid()
        val edited = first.copy(customerNote = "Sem açúcar")
        assertEquals(first.submissionId, edited.submissionId)
        assertTrue(first.submissionId.matches(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
        assertNotEquals(first.submissionId, ManualOrderDraft().submissionId)
    }

    @Test fun totalUsesExactDecimalCents() {
        val order = valid().copy(items = listOf(
            ManualOrderItem(name = "Açaí", quantity = "3", unitPrice = "0,10"),
            ManualOrderItem(name = "Adicional", quantity = "2", unitPrice = "0,20")
        ))
        assertNull(order.validate())
        assertEquals(BigDecimal("0.70"), order.total)
    }

    @Test fun rejectsInvalidPaymentAndFulfillment() {
        assertNotNull(valid().copy(paymentMethod = "CARTAO_DESCONHECIDO").validate())
        assertNotNull(valid().copy(fulfillment = "MOTOBOY").validate())
    }
}
