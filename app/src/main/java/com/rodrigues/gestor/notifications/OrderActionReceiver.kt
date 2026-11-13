package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rodrigues.gestor.NativeGestorActivity

/**
 * A ação da notificação ABRE o pedido para confirmação online.
 * Nunca aceita automaticamente um pedido offline ou por fila.
 */
class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                if (orderId.isBlank()) return
                val open = Intent(context, NativeGestorActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(NativeGestorActivity.EXTRA_ORDER_ID, orderId)
                }
                context.startActivity(open)
            }
        }
    }
    companion object {
        const val ACTION_ACCEPT = "com.rodrigues.gestor.ACCEPT_ORDER"
        const val ACTION_SILENCE = "com.rodrigues.gestor.SILENCE_ORDER"
        const val EXTRA_ORDER_ID = "order_id"
    }
}
