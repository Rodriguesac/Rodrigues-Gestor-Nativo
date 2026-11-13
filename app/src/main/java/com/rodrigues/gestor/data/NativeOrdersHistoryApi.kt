package com.rodrigues.gestor.data

import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Busca dados reais da gestor-app-api, nunca cria histórico de exemplo.
 * O servidor limita a página a 200 pedidos; não afirmar que a lista é completa.
 */
object NativeOrdersHistoryApi {
    private fun iso(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    fun loadLast30Days(
        onSuccess: (List<Order>, Boolean) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val end = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }.time
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -30) }.time
        val data = JSONObject().put("history", true).put("from", iso(start))
            .put("to", iso(end)).put("offset", 0)
        NativeCatalogApi.execute("orders", data, onSuccess = { response ->
            val records = response.optJSONArray("orders") ?: JSONArray()
            val normalized = SupabaseOrdersApi.parseOrders(records).filter { order ->
                val status = order.status.uppercase(Locale.ROOT)
                status in StatusGroups.DONE || status in StatusGroups.CANCELED
            }
            onSuccess(normalized, records.length() >= 200)
        }, onError = onError)
    }
}
