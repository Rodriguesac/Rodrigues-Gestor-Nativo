package com.rodrigues.gestor.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GestorMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        DeviceRegistrar.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        NotificationHelper.createChannels(this)
        val data = message.data
        val type = (data["type"] ?: data["tipo"] ?: "").uppercase()
        val orderId = data["orderId"] ?: data["pedidoId"] ?: ""
        val number = data["number"] ?: data["numeroPedido"] ?: orderId.takeLast(6).uppercase()
        val client = data["clientName"] ?: data["clienteNome"] ?: "Cliente"
        val body = data["body"] ?: data["mensagem"] ?: message.notification?.body ?: "Abra o Gestor para ver."

        when (type) {
            "NEW_ORDER", "NOVO_PEDIDO", "PEDIDO_NOVO" -> {
                if (orderId.isNotBlank() && AlertPreferences.enabled(this)) OrderRingService.start(this, orderId, number, client)
            }
            "CLIENT_MESSAGE", "MENSAGEM_CLIENTE" -> {
                if (AlertPreferences.messageAlerts(this)) NotificationHelper.showMessage(this, "Mensagem do cliente", body, orderId)
            }
            "ALTERACAO_PEDIDO", "ALTERAÇÃO_PEDIDO" -> {
                if (AlertPreferences.changeAlerts(this)) NotificationHelper.showMessage(this, "Alteração solicitada", body, orderId)
            }
            "DRIVER_UPDATE", "UP_ENTREGAS", "ENTREGADOR", "CORRIDA" -> {
                if (AlertPreferences.driverAlerts(this)) NotificationHelper.showMessage(this, "UP Entregas", body, orderId)
            }
            "PAYMENT", "PAGAMENTO", "PAGAMENTO_APROVADO", "PAGAMENTO_RECUSADO" -> {
                if (AlertPreferences.paymentAlerts(this)) NotificationHelper.showMessage(this, "Pagamento", body, orderId)
            }
            else -> {
                val title = message.notification?.title ?: data["title"] ?: "Rodrigues Gestor"
                NotificationHelper.showMessage(this, title, body, orderId)
            }
        }
    }
}
