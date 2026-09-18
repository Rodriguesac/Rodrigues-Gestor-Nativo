package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.SupabaseOrdersApi

class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                if (orderId.isBlank()) return
                GestorCredentials.load(context)
                val pending = goAsync()
                SupabaseOrdersApi.updateStatus(orderId, "CONFIRMADO", {
                    OrderRingService.stopFor(context, orderId)
                    NotificationHelper.cancelOrder(context, orderId)
                    pending.finish()
                }, {
                    NotificationHelper.showMessage(context, "Pedido não confirmado", it.message ?: "Abra o Gestor para conferir.", orderId)
                    pending.finish()
                })
            }
        }
    }
    companion object {
        const val ACTION_ACCEPT = "com.rodrigues.gestor.ACCEPT_ORDER"
        const val ACTION_SILENCE = "com.rodrigues.gestor.SILENCE_ORDER"
        const val EXTRA_ORDER_ID = "order_id"
    }
}
