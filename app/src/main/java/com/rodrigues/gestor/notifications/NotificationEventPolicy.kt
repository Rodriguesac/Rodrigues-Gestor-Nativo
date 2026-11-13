package com.rodrigues.gestor.notifications

import java.util.Locale

/**
 * Não reutilizar 'novo_<id>' para eventos de tipos diferentes.
 * Push de pagamento e mensagem não pode ser descartado só porque
 * já houve aviso de novo pedido no mesmo pedido.
 */
object NotificationEventPolicy {
    fun normalizeType(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun key(
        providerEventId: String?,
        messageId: String?,
        type: String,
        orderId: String,
        state: String?
    ): String {
        val original = providerEventId?.trim().orEmpty()
        if (original.isNotBlank()) return "event:$original"
        val message = messageId?.trim().orEmpty()
        if (message.isNotBlank()) return "message:$message"
        if (orderId.isBlank()) return ""
        val kind = normalizeType(type).ifBlank { "GERAL" }
        return "order:$kind:$orderId:${state?.trim().orEmpty()}"
    }
}
