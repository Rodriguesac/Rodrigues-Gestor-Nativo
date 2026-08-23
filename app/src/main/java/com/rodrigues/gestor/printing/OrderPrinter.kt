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
        printReceipt(context, order, copies, paperWidthMm)
    }

    fun printReceipt(context: Context, order: Order, copies: Int = 1, paperWidthMm: Int = 80) {
        val safeCopies = copies.coerceIn(1, 3)
        val width = if (paperWidthMm == 58) 58 else 80
        val html = buildHtml(order, safeCopies, width, a4 = false)
        val widthMils = if (width == 58) 2_283 else 3_150
        val mediaSize = PrintAttributes.MediaSize(
            "RODRIGUES_RECEIPT_${width}MM",
            "Comanda $width mm",
            widthMils,
            11_693,
        )
        launchPrint(context, order, html, mediaSize, noMargins = true)
    }

    fun printA4(context: Context, order: Order) {
        val html = buildHtml(order, copies = 1, width = 80, a4 = true)
        launchPrint(context, order, html, PrintAttributes.MediaSize.ISO_A4, noMargins = false)
    }

    private fun launchPrint(
        context: Context,
        order: Order,
        html: String,
        mediaSize: PrintAttributes.MediaSize,
        noMargins: Boolean,
    ) {
        val webView = WebView(context)
        var started = false
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter("Pedido-${order.number}")
                val attributes = PrintAttributes.Builder()
                    .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                    .setMediaSize(mediaSize)
                    .apply { if (noMargins) setMinMargins(PrintAttributes.Margins.NO_MARGINS) }
                    .build()
                manager.print(
                    "Pedido-${order.number}",
                    adapter,
                    attributes,
                )
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun buildHtml(order: Order, copies: Int, width: Int, a4: Boolean): String {
        val page = buildString {
            append("<section class='ticket'>")
            append("<header><h1>RODRIGUES AÇAÍ E CIA</h1><h2>PEDIDO #${esc(order.number)}</h2></header>")
            append("<div class='rule'></div>")
            append("<div class='customer'><p><b>Cliente:</b> ${esc(order.clientName)}</p>")
            if (order.phone.isNotBlank()) append("<p><b>Telefone:</b> ${esc(order.phone)}</p>")
            append("<p><b>Tipo:</b> ${if (order.pickup) "RETIRADA" else "ENTREGA"}</p>")
            if (!order.pickup && order.address.isNotBlank()) append("<p><b>Endereço:</b> ${esc(order.address)}</p>")
            append("</div>")
            append("<div class='rule'></div>")
            append("<div class='items'>")
            order.items.forEach { item ->
                append("<div class='item'><b>${item.quantity}x</b><span>${esc(item.name)}</span><b>${if (item.price > 0) esc(money(item.price)) else ""}</b></div>")
                item.details.forEach { append("<div class='detail'>• ${esc(it)}</div>") }
            }
            append("</div>")
            if (order.observation.isNotBlank()) {
                append("<div class='rule'></div><p class='obs'><b>OBS:</b> ${esc(order.observation)}</p>")
            }
            append("<div class='rule'></div>")
            append("<div class='summary'>")
            if (order.subtotal > 0) append("<p><span>Subtotal</span><b>${esc(money(order.subtotal))}</b></p>")
            if (order.freight > 0) append("<p><span>Entrega</span><b>${esc(money(order.freight))}</b></p>")
            if (order.discount > 0) append("<p><span>Desconto</span><b>- ${esc(money(order.discount))}</b></p>")
            append("</div>")
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
        val pageCss = if (a4) "@page{size:A4;margin:12mm}" else "@page{margin:2mm}"
        val bodyCss = if (a4) {
            "font-family:Arial,sans-serif;font-size:14px;background:#fff;padding:0;color:#171117"
        } else {
            "font-family:monospace;font-size:${if (width == 58) "11px" else "13px"};color:#000"
        }
        val ticketCss = if (a4) {
            "width:100%;max-width:185mm;margin:0 auto;border:1px solid #ddd;border-radius:12px;padding:10mm;box-sizing:border-box"
        } else {
            "width:${width}mm;max-width:100%;margin:0 auto;box-sizing:border-box;padding:1.5mm"
        }
        return """
            <!doctype html><html><head><meta charset='utf-8'>
            <style>
            $pageCss *{box-sizing:border-box}body{margin:0;$bodyCss}.ticket{$ticketCss}.ticket h1{text-align:center;font-size:1.15em;margin:0 0 3px}.ticket h2{text-align:center;font-size:1.55em;margin:0 0 6px}
            p{margin:4px 0;line-height:1.35}.rule{border-top:1px dashed #000;margin:9px 0}.item{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:8px;align-items:start;margin:8px 0;font-size:1.04em}.detail{padding-left:28px;margin:-3px 0 4px;line-height:1.35}.obs{font-size:1.08em;border:1px solid #000;padding:8px}.summary p{display:flex;justify-content:space-between}.total{display:flex;justify-content:space-between;font-size:1.45em;margin:10px 0}.break{page-break-after:always}
            ${if (a4) ".customer{display:grid;grid-template-columns:1fr 1fr;column-gap:18px}.items{font-size:1.06em}.detail{padding-left:34px}" else ""}
            </style></head><body>$pages</body></html>
        """.trimIndent()
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
