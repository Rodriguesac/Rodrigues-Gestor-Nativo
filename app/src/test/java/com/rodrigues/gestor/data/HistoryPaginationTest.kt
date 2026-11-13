package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test

class HistoryPaginationTest {
    private fun order(id: String, age: Long) = Order(
        id = id, number = id, status = "CONCLUIDO",
        clientName = "Cliente", clientUid = "", phone = "", address = "",
        neighborhood = "", items = emptyList(), observation = "",
        total = 12.0, subtotal = 12.0, freight = 0.0, discount = 0.0,
        createdMillis = age, payment = PaymentInfo(form = "PIX", status = "PAGO"),
        pickup = true, raw = emptyMap()
    )

    @Test fun offsetCountsBackendRowsBeforeLocalStatusFilter() {
        // A resposta com 200 linhas pode conter apenas 20 pedidos encerrados.
        val page = HistoryPage(List(20) { order(it.toString(), it.toLong()) }, 200)
        assertTrue(page.hasMore)
        assertEquals(200, HistoryPagination.nextOffset(0, page.rawCount))
        assertEquals(400, HistoryPagination.nextOffset(200, page.rawCount))
    }

    @Test fun finalPageStopsFurtherPaging() {
        val last = HistoryPage(listOf(order("one", 42)), 83)
        assertFalse(last.hasMore)
        assertEquals(483, HistoryPagination.nextOffset(400, last.rawCount))
    }

    @Test fun mergeDoesNotDuplicateOrdersFromAnotherPage() {
        val old = listOf(order("a", 100), order("b", 90))
        val page = listOf(order("b", 95), order("c", 105))
        val merged = HistoryPagination.merge(old, page)
        assertEquals(3, merged.size)
        assertEquals(listOf("c", "a", "b"), merged.map { it.id })
    }

    @Test fun invalidOffsetsDoNotSilentlyAdvance() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPagination.nextOffset(1, 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPagination.nextOffset(-200, 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryPagination.nextOffset(0, 201)
        }
    }

    @Test fun periodsCoverInclusiveTodayAndPastDays() {
        for (days in listOf(1, 7, 30, 90)) {
            val window = NativeOrdersHistoryApi.window(days)
            assertTrue(window.toIso > window.fromIso)
            assertTrue(window.fromIso.endsWith("+00:00") || window.fromIso.endsWith("Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeOrdersHistoryApi.window(366)
        }
    }
}
