package com.rodrigues.gestor.data
import org.junit.Assert.*
import org.junit.Test
class OrderStateTest {
    @Test fun completedAndCanceledAliasesNeverEnterActiveOrders() {
        for (status in listOf("completed", "DELIVERED", "CANCELED", "CANCELLED", "CANCELADO")) {
            val order = normalizeOrder("id", mapOf("status" to status, "createdAt" to 1000L))
            assertTrue(order.status in StatusGroups.DONE || order.status in StatusGroups.CANCELED)
            assertFalse(order.canAlert(2000L))
        }
    }
    @Test fun ringsOnlyPendingInsideEightMinuteDeadline() {
        val order = normalizeOrder("id", mapOf("status" to "RECEBIDO", "createdAt" to 1000L))
        assertTrue(order.canAlert(1000L))
        assertTrue(order.canAlert(480999L))
        assertFalse(order.canAlert(481000L))
        assertFalse(order.canAlert(999L))
        assertFalse(order.copy(status = "CONFIRMADO").canAlert(2000L))
        assertFalse(order.copy(status = "CANCELADO").canAlert(2000L))
        assertFalse(order.copy(createdMillis = 0).canAlert(2000L))
    }
    @Test fun confirmedOrdersRemainVisibleAndAddressUsesNormalizedColumns() {
        val order = normalizeOrder("id", mapOf("status" to " accepted ", "endereco" to mapOf("street" to "Rua A", "number" to "10", "neighborhood" to "Centro")))
        assertTrue(order.status in StatusGroups.CONFIRMED)
        assertEquals("Rua A, 10, Centro", order.address)
    }
}
