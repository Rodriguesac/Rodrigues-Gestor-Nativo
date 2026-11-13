package com.rodrigues.gestor.printing

import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.OrderItem
import com.rodrigues.gestor.data.PaymentInfo
import org.junit.Assert.*
import org.junit.Test

class OrderPdfExporterTest {
    private fun sampleOrder() = Order(
        id = "fake-123",
        number = "123/456 <EXTRA>",
        status = "PRONTO",
        clientName = "Cliente Teste",
        clientUid = "",
        phone = "67999999999",
        address = "Rua das Flores, 42, Centro",
        neighborhood = "Centro",
        items = listOf(OrderItem("Açaí 1L", 2, 38.00, listOf("Leite em pó", "Banana"))),
        observation = "Sem açúcar",
        total = 82.00,
        subtotal = 76.00,
        freight = 6.00,
        discount = 0.00,
        createdMillis = 0L,
        payment = PaymentInfo(form = "PIX", status = "PENDENTE"),
        pickup = false,
        raw = emptyMap()
    )

    @Test fun pdfHasStatusCustomerProductsAndNotFiscalText() {
        val lines = OrderPdfExporter.receiptLines(sampleOrder()).joinToString("\n")
        assertTrue(lines.contains("Cliente Teste"))
        assertTrue(lines.contains("Rua das Flores"))
        assertTrue(lines.contains("Açaí 1L"))
        assertTrue(lines.contains("Leite em pó"))
        assertTrue(lines.contains("Pagamento: PIX"))
        assertTrue(lines.contains("Pagamento pendente"))
        assertTrue(lines.contains("NÃO FISCAL"))
        assertFalse(lines.contains("Pagamento confirmado"))
    }

    @Test fun fileNameIsSafe() {
        val filename = OrderPdfExporter.proposedFileName(sampleOrder())
        assertTrue(filename.endsWith(".pdf"))
        assertFalse(filename.contains("/"))
        assertFalse(filename.contains("<"))
        assertTrue(filename.length < 80)
    }
}
