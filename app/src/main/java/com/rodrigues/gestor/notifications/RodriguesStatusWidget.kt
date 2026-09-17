package com.rodrigues.gestor.notifications

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rodrigues.gestor.MainActivity
import com.rodrigues.gestor.R

class RodriguesStatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val summary = DailyOrderSummary.load(context)
        appWidgetIds.forEach { appWidgetId ->
            appWidgetManager.updateAppWidget(appWidgetId, views(context, summary))
        }
    }

    companion object {
        fun updateAll(context: Context, summary: DailyOrderSummary = DailyOrderSummary.load(context)) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, RodriguesStatusWidget::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { manager.updateAppWidget(it, views(context, summary)) }
        }

        private fun views(context: Context, summary: DailyOrderSummary): RemoteViews {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                4701,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return RemoteViews(context.packageName, R.layout.widget_rodrigues_summary).apply {
                setTextViewText(R.id.widget_count_new, summary.newOrders.toString())
                setTextViewText(R.id.widget_count_queue, summary.queue.toString())
                setTextViewText(R.id.widget_count_preparing, summary.preparing.toString())
                setTextViewText(R.id.widget_count_ready, summary.ready.toString())
                setTextViewText(R.id.widget_count_delivery, summary.delivery.toString())
                setTextViewText(R.id.widget_count_completed, summary.completed.toString())
                setTextViewText(R.id.widget_count_canceled, summary.canceled.toString())
                setOnClickPendingIntent(R.id.widget_root, pending)
            }
        }
    }
}
