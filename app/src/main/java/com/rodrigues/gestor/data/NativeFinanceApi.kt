package com.rodrigues.gestor.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Total financeiro só de pedidos CONCLUÍDOS, calculado no Supabase. */
data class FinanceSummary(
    val orders: Int = 0,
    val completed: Int = 0,
    val cancelled: Int = 0,
    val revenue: Double = 0.0,
    val discounts: Double = 0.0,
    val deliveries: Double = 0.0,
    val byPayment: Map<String, Double> = emptyMap()
)

object NativeFinanceApi {
    private fun iso(calendar: Calendar) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = calendar.timeZone }.format(calendar.time)

    fun load(days: Int, onSuccess: (FinanceSummary) -> Unit, onError: (Throwable) -> Unit) {
        if (days !in setOf(1, 7, 30)) {
            onError(IllegalArgumentException("Período inválido."))
            return
        }
        val begin = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -(days - 1))
        }
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        NativeCatalogApi.execute("report", JSONObject().put("from", iso(begin))
            .put("to", iso(end)), onSuccess = { response ->
            val report = response.optJSONObject("report") ?: JSONObject()
            val payments = report.optJSONObject("byPayment") ?: JSONObject()
            val paymentTypes = linkedMapOf<String, Double>()
            val keys = payments.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                paymentTypes[key] = payments.optDouble(key, 0.0)
            }
            onSuccess(FinanceSummary(
                orders = report.optInt("count", 0),
                completed = report.optInt("completed", 0),
                cancelled = report.optInt("cancelled", 0),
                revenue = report.optDouble("total", 0.0),
                discounts = report.optDouble("discount", 0.0),
                deliveries = report.optDouble("delivery", 0.0),
                byPayment = paymentTypes.toMap()
            ))
        }, onError = onError)
    }
}
