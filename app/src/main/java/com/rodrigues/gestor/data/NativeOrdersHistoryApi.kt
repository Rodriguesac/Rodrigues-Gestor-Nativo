package com.rodrigues.gestor.data

import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Janela temporal estável entre páginas, para não pular pedidos. */
data class HistoryWindow(val fromIso: String, val toIso: String)

/**
 * Offset avança pelos registros BRUTOS retornados pelo backend, nunca pelo
 * número já filtrado para concluídos/cancelados.
 */
data class HistoryPage(val orders: List<Order>, val rawCount: Int) {
    val hasMore: Boolean get() = rawCount >= NativeOrdersHistoryApi.PAGE_SIZE
}

object HistoryPagination {
    fun nextOffset(previous: Int, rawCount: Int): Int {
        require(previous >= 0 && previous % NativeOrdersHistoryApi.PAGE_SIZE == 0)
        require(rawCount in 0..NativeOrdersHistoryApi.PAGE_SIZE)
        return previous + rawCount
    }

    fun merge(previous: List<Order>, page: List<Order>): List<Order> =
        (previous + page).distinctBy { it.id }.sortedByDescending { it.createdMillis }
}

object NativeOrdersHistoryApi {
    const val PAGE_SIZE = 200
    const val MAX_OFFSET = 20000

    private fun iso(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    fun window(days: Int = 30): HistoryWindow {
        require(days in setOf(1, 7, 30, 90)) { "Período inválido." }
        val end = Calendar.getInstance().apply {
            // A janela termina no início do dia seguinte, sempre no
            // fuso local da loja, inclusive com mudança de data à meia-noite.
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = (end.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -days) }
        return HistoryWindow(iso(start.time), iso(end.time))
    }

    fun loadPage(window: HistoryWindow, offset: Int,
                 onSuccess: (HistoryPage) -> Unit, onError: (Throwable) -> Unit) {
        if (offset !in 0..MAX_OFFSET || offset % PAGE_SIZE != 0) {
            onError(IllegalArgumentException("Página de histórico inválida."))
            return
        }
        val data = JSONObject()
            .put("history", true)
            .put("from", window.fromIso)
            .put("to", window.toIso)
            .put("offset", offset)
        NativeCatalogApi.execute("orders", data, onSuccess = { response ->
            try {
                val rows = response.optJSONArray("orders") ?: JSONArray()
                val parsed = SupabaseOrdersApi.parseOrders(rows).filter { order ->
                    val status = order.status.uppercase(Locale.ROOT)
                    status in StatusGroups.DONE || status in StatusGroups.CANCELED
                }
                onSuccess(HistoryPage(parsed, rows.length()))
            } catch (error: Throwable) { onError(error) }
        }, onError = onError)
    }
}
