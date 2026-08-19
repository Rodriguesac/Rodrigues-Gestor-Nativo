package com.rodrigues.gestor.printing

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.money

object OrderPrinter {
    fun print(context: Context, order: Order, copies: Int = 1, paperWidthMm: Int = 80) {
        val safeCopies = copies.coerceIn(1, 3)
        val width = if (paperWidthMm == 58) 58 else 80
        val html = buildHtml(order, safeCopies, width)
        val webView = WebView(context)
        var started = false
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter("Pedido-${order.number}")
                manager.print(
                    "Pedido-${order.number}",
                    adapter,
                    PrintAttributes.Builder().setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build()
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun buildHtml(order: Order, copies: Int, width: Int): String {
        val page = buildString {
            append("<section class='ticket'>")
            append("<h1>RODRIGUES AÇAÍ E CIA</h1>")
            append("<h2>PEDIDO #${esc(order.number)}</h2>")
            append("<div class='rule'></div>")
            append("<p><b>Cliente:</b> ${esc(order.clientName)}</p>")
            if (order.phone.isNotBlank()) append("<p><b>Telefone:</b> ${esc(order.phone)}</p>")
            append("<p><b>Tipo:</b> ${if (order.pickup) "RETIRADA" else "ENTREGA"}</p>")
            if (!order.pickup && order.address.isNotBlank()) append("<p><b>Endereço:</b> ${esc(order.address)}</p>")
            append("<div class='rule'></div>")
            order.items.forEach { item ->
                append("<div class='item'><b>${item.quantity}x</b><span>${esc(item.name)}</span><b>${if (item.price > 0) esc(money(item.price)) else ""}</b></div>")
                item.details.forEach { append("<div class='detail'>• ${esc(it)}</div>") }
            }
            if (order.observation.isNotBlank()) {
                append("<div class='rule'></div><p class='obs'><b>OBS:</b> ${esc(order.observation)}</p>")
            }
            append("<div class='rule'></div>")
            append("<div class='total'><span>TOTAL</span><b>${esc(money(order.total))}</b></div>")
            append("<p><b>Pagamento:</b> ${esc(order.payment.form)}")
            if (order.payment.status.isNotBlank()) append(" • ${esc(order.payment.status.uppercase())}")
            append("</p>")
            if (order.payment.needsChange) append("<p><b>Troco para:</b> ${esc(money(order.payment.changeFor))}</p>")
            append("</section>")
        }
        val pages = (1..copies).joinToString("") { idx ->
            if (idx == copies) page else "$page<div class='break'></div>"
        }
        return """
            <!doctype html><html><head><meta charset='utf-8'>
            <style>
            @page{margin:4mm} body{font-family:monospace;color:#000;margin:0;font-size:${if (width==58) "11px" else "13px"};}
            .ticket{width:${width}mm;max-width:100%;margin:0 auto}.ticket h1{text-align:center;font-size:1.15em;margin:0 0 3px}.ticket h2{text-align:center;font-size:1.45em;margin:0 0 6px}
            p{margin:3px 0;line-height:1.3}.rule{border-top:1px dashed #000;margin:7px 0}.item{display:grid;grid-template-columns:auto 1fr auto;gap:6px;align-items:start;margin:6px 0}.detail{padding-left:25px;margin:-2px 0 3px}.obs{font-size:1.08em}.total{display:flex;justify-content:space-between;font-size:1.35em;margin:8px 0}.break{page-break-after:always}
            </style></head><body>$pages</body></html>
        """.trimIndent()
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
