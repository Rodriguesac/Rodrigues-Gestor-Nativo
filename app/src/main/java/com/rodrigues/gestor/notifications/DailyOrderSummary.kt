package com.rodrigues.gestor.notifications

import android.content.Context
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.StatusGroups
import java.util.Calendar
import java.util.Locale

/** Contadores operacionais do dia atual, usados por notificação, widget e painel rápido. */
data class DailyOrderSummary(
    val newOrders: Int = 0,
    val queue: Int = 0,
    val preparing: Int = 0,
    val ready: Int = 0,
    val delivery: Int = 0,
    val completed: Int = 0,
    val canceled: Int = 0,
    val updatedAt: Long = 0L,
) {
    val active: Int get() = newOrders + queue + preparing + ready + delivery

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NEW, newOrders)
            .putInt(KEY_QUEUE, queue)
            .putInt(KEY_PREPARING, preparing)
            .putInt(KEY_READY, ready)
            .putInt(KEY_DELIVERY, delivery)
            .putInt(KEY_COMPLETED, completed)
            .putInt(KEY_CANCELED, canceled)
            .putLong(KEY_UPDATED, updatedAt)
            .apply()
    }

    companion object {
        private const val PREFS = "rodrigues_daily_order_summary"
        private const val KEY_NEW = "new"
        private const val KEY_QUEUE = "queue"
        private const val KEY_PREPARING = "preparing"
        private const val KEY_READY = "ready"
        private const val KEY_DELIVERY = "delivery"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_CANCELED = "canceled"
        private const val KEY_UPDATED = "updated"

        fun fromOrders(orders: List<Order>, now: Long = System.currentTimeMillis()): DailyOrderSummary {
            val (start, end) = currentDayBounds(now)
            var newOrders = 0
            var queue = 0
            var preparing = 0
            var ready = 0
            var delivery = 0
            var completed = 0
            var canceled = 0

            orders.asSequence()
                .filter { it.createdMillis in start until end }
                .forEach { order ->
                    val status = order.status.trim().uppercase(Locale.ROOT)
                    when {
                        status in StatusGroups.NEW -> newOrders++
                        status in StatusGroups.CONFIRMED -> queue++
                        status in StatusGroups.PREPARING -> preparing++
                        status in StatusGroups.READY -> ready++
                        status in StatusGroups.DELIVERY || status == "DESPACHADO" -> delivery++
                        status in StatusGroups.DONE -> completed++
                        status in StatusGroups.CANCELED || status in setOf("CANCELED", "CANCELLED") -> canceled++
                    }
                }

            return DailyOrderSummary(
                newOrders = newOrders,
                queue = queue,
                preparing = preparing,
                ready = ready,
                delivery = delivery,
                completed = completed,
                canceled = canceled,
                updatedAt = now,
            )
        }

        fun load(context: Context): DailyOrderSummary {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = DailyOrderSummary(
                newOrders = prefs.getInt(KEY_NEW, 0),
                queue = prefs.getInt(KEY_QUEUE, 0),
                preparing = prefs.getInt(KEY_PREPARING, 0),
                ready = prefs.getInt(KEY_READY, 0),
                delivery = prefs.getInt(KEY_DELIVERY, 0),
                completed = prefs.getInt(KEY_COMPLETED, 0),
                canceled = prefs.getInt(KEY_CANCELED, 0),
                updatedAt = prefs.getLong(KEY_UPDATED, 0L),
            )
            return if (saved.updatedAt > 0L && isSameLocalDay(saved.updatedAt, System.currentTimeMillis())) {
                saved
            } else {
                DailyOrderSummary()
            }
        }

        private fun currentDayBounds(now: Long): Pair<Long, Long> {
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            return start.timeInMillis to end.timeInMillis
        }

        private fun isSameLocalDay(a: Long, b: Long): Boolean {
            val ca = Calendar.getInstance().apply { timeInMillis = a }
            val cb = Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(Calendar.ERA) == cb.get(Calendar.ERA) &&
                ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }
    }
}
