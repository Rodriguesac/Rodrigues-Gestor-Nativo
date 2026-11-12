package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rodrigues.gestor.data.OfflineOrderStore
import com.rodrigues.gestor.data.OfflineOrderSyncWorker
import com.rodrigues.gestor.data.StatusGroups

/**
 * O botão ACEITAR da notificação alimenta a mesma fila offline da interface.
 * Somente a API canônica confirma o aceite depois da sincronização.
 */
class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                if (orderId.isBlank()) return
                val store = OfflineOrderStore(context)
                try {
                    val current = store.cachedOrders().firstOrNull { it.id == orderId }
                    if (current == null || current.status !in StatusGroups.NEW) {
                        NotificationHelper.showMessage(
                            context, "Confira o pedido",
                            "Não foi possível validar o estado local. Abra o Gestor para conferir.",
                            orderId
                        )
                        return
                    }
                    if (store.enqueue(orderId, "CONFIRMADO", current.status)) {
                        OrderRingService.stop(context)
                        NotificationHelper.cancelOrder(context, orderId)
                        NotificationHelper.showMessage(
                            context, "Aceite registrado no aparelho",
                            "Pedido #${current.number} aguardando confirmação do servidor.",
                            orderId
                        )
                        OfflineOrderSyncWorker.schedule(context)
                    } else {
                        NotificationHelper.showMessage(
                            context, "Pedido com ação pendente",
                            "Já existe uma alteração desse pedido aguardando sincronização.",
                            orderId
                        )
                    }
                } catch (error: Throwable) {
                    NotificationHelper.showMessage(
                        context, "Falha ao salvar o aceite",
                        error.message ?: "Abra o Gestor para conferir.", orderId
                    )
                } finally {
                    store.close()
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
