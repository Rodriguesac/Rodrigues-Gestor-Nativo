package com.rodrigues.gestor.printing

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.PaymentStatusDisplay
import com.rodrigues.gestor.data.money
import java.io.IOException
import java.util.Locale

/**
 * Cria PDF real pelo Storage Access Framework; não exige gerenciador de
 * arquivos, impressora, servidor, permissões de armazenamento ou WebView.
 *
 * O destino é escolhido explicitamente pelo gestor em ACTION_CREATE_DOCUMENT.
 * NÃO é um documento fiscal.
 */
object OrderPdfExporter {
    const val MIME_TYPE = "application/pdf"

    fun proposedFileName(order: Order): String {
        val safe = order.number.filter { it.isLetterOrDigit() || it == '-' }.take(25)
        return "Rodrigues-Pedido-\${safe.ifBlank { "comanda" }}.pdf"
    }

    fun receiptLines(order: Order): List<String> = buildList {
        add("RODRIGUES AÇAÍ E CIA")
        add("COMANDA DE PEDIDO #\${order.number}")
        add("DOCUMENTO NÃO FISCAL")
        add("--------------------------------------------")
        add("Cliente: \${order.clientName}")
        if (order.phone.isNotBlank()) add("Telefone: \${order.phone}")
        add("Modalidade: \${if (order.pickup) "RETIRADA" else "ENTREGA"}")
        if (!order.pickup && order.address.isNotBlank()) add("Endereço: \${order.address}")
        add("Status do pedido: \${order.status.uppercase(Locale.ROOT)}")
        add("--------------------------------------------")
        add("ITENS")
        order.items.forEach { item ->
            add("\${item.quantity}x \${item.name}" +
                (if (item.price > 0) " — \${money(item.price)}" else ""))
            item.details.forEach { add("   + $it") }
        }
        if (order.observation.isNotBlank()) {
            add("--------------------------------------------")
            add("OBSERVAÇÃO: \${order.observation}")
        }
        add("--------------------------------------------")
        if (order.subtotal > 0) add("Subtotal: \${money(order.subtotal)}")
        if (order.freight > 0) add("Entrega: \${money(order.freight)}")
        if (order.discount > 0) add("Desconto: - \${money(order.discount)}")
        add("TOTAL: \${money(order.total)}")
        add("Pagamento: \${order.payment.form}")
        add("Situação: \${PaymentStatusDisplay.label(order.payment.status)}")
        if (order.payment.needsChange) add("Troco para: \${money(order.payment.changeFor)}")
        add("--------------------------------------------")
        add("Este documento não comprova pagamento nem substitui nota fiscal.")
    }

    fun save(context: Context, order: Order, destination: Uri) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val lineHeight = 18f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        var page: PdfDocument.Page? = null
        var y = margin
        var pageNumber = 0
        fun beginPage() {
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(
                pageWidth, pageHeight, pageNumber).create())
            y = margin
        }
        fun finishPage() {
            page?.let { document.finishPage(it) }
            page = null
        }
        fun drawLine(value: String) {
            if (page == null) beginPage()
            if (y > pageHeight - margin) {
                finishPage()
                beginPage()
            }
            page!!.canvas.drawText(value, margin, y, paint)
            y += lineHeight
        }
        fun wrap(raw: String): List<String> {
            val maxWidth = pageWidth - margin * 2
            val result = mutableListOf<String>()
            raw.split('\n').forEach { source ->
                var line = ""
                val words = source.split(Regex("\\s+"))
                for (word in words) {
                    if (word.isEmpty()) continue
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(candidate) <= maxWidth) {
                        line = candidate
                        continue
                    }
                    if (line.isNotEmpty()) {
                        result.add(line)
                        line = ""
                    }
                    if (paint.measureText(word) <= maxWidth) {
                        line = word
                    } else {
                        var fragment = ""
                        for (char in word) {
                            if (paint.measureText(fragment + char) > maxWidth && fragment.isNotEmpty()) {
                                result.add(fragment)
                                fragment = ""
                            }
                            fragment += char
                        }
                        line = fragment
                    }
                }
                result.add(line)
            }
            return result
        }

        try {
            receiptLines(order).forEach { text ->
                wrap(text).forEach(::drawLine)
            }
            finishPage()
            val stream = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("Não foi possível abrir o arquivo PDF escolhido.")
            stream.use { document.writeTo(it) }
        } finally {
            // Também fecha recursos caso a gravação falhe.
            try { finishPage() } finally { document.close() }
        }
    }
}
