package com.rodrigues.gestor.data

import org.json.JSONArray
import org.json.JSONObject

data class NativeChatMessage(
    val id: String,
    val body: String,
    val senderType: String,
    val createdAt: String
)

object NativeChatApi {
    fun openConversation(order: Order, onSuccess: (String) -> Unit,
                         onError: (Throwable) -> Unit) {
        val args = JSONObject().put("order_id", order.id)
        val customerId = order.raw["customer_id"]?.toString().orEmpty()
        if (customerId.isNotBlank()) args.put("customer_id", customerId)
        NativeCatalogApi.execute("conversation_for_order", args, onSuccess = { response ->
            val id = response.optJSONObject("conversation")?.optString("id").orEmpty()
            if (id.isBlank()) onError(IllegalStateException("Conversa não foi identificada."))
            else onSuccess(id)
        }, onError = onError)
    }

    fun messages(conversationId: String, onSuccess: (List<NativeChatMessage>) -> Unit,
                 onError: (Throwable) -> Unit) {
        NativeCatalogApi.execute("messages", JSONObject().put("conversation_id", conversationId),
            onSuccess = { response ->
                val rows = response.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (i in 0 until rows.length()) {
                        val msg = rows.optJSONObject(i) ?: continue
                        add(NativeChatMessage(
                            msg.optString("id", i.toString()),
                            msg.optString("body"),
                            msg.optString("sender_type"),
                            msg.optString("created_at")
                        ))
                    }
                }
                onSuccess(messages)
            }, onError = onError)
    }

    fun send(conversationId: String, message: String, onSuccess: () -> Unit,
             onError: (Throwable) -> Unit) {
        val clean = message.trim()
        if (clean.isBlank() || clean.length > 2000) {
            onError(IllegalArgumentException("Escreva uma mensagem de até 2.000 caracteres."))
            return
        }
        NativeCatalogApi.execute("send_message", JSONObject()
            .put("conversation_id", conversationId)
            .put("body", clean),
            onSuccess = { onSuccess() },
            onError = onError)
    }
}
