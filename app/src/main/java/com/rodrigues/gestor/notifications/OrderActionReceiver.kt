package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.SupabaseOrdersApi

/**
 * O pedido canônico é do Supabase. Aceitar na notificação não deve mais
 * escrever apenas na coleção Firestore legada.
 */
class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                if (orderId.isBlank()) return
                GestorCredentials.load(context)
                val pending = goAsync()
                try {
                    SupabaseOrdersApi.updateStatus(
                        orderId = orderId,
                        status = "CONFIRMADO",
                        onDone = {
                            OrderRingService.stop(context)
                            NotificationHelper.cancelOrder(context, orderId)
                            pending.finish()
                        },
                        onError = {
                            NotificationHelper.showMessage(
                                context,
                                "Pedido não confirmado",
                                it.message ?: "Abra o Gestor e confira o status do pedido.",
                                orderId
                            )
                            pending.finish()
                        }
                    )
                } catch (error: Throwable) {
                    NotificationHelper.showMessage(
                        context,
                        "Pedido não confirmado",
                        error.message ?: "Não foi possível confirmar o pedido.",
                        orderId
                    )
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.rodrigues.gestor.ACCEPT_ORDER"
        const val ACTION_SILENCE = "com.rodrigues.gestor.SILENCE_ORDER"
        const val EXTRA_ORDER_ID = "order_id"
    }
}
