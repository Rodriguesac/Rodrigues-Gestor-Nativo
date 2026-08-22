package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                if (orderId.isBlank()) return
                val pending = goAsync()
                val patch = mapOf<String, Any>(
                    "status" to "CONFIRMADO",
                    "statusPedido" to "CONFIRMADO",
                    "statusLoja" to "CONFIRMADO",
                    "aceitoEm" to FieldValue.serverTimestamp(),
                    "acceptedAt" to FieldValue.serverTimestamp(),
                    "statusAtualizadoEm" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "historicoStatus" to FieldValue.arrayUnion(
                        mapOf(
                            "status" to "CONFIRMADO",
                            "titulo" to "Em preparo",
                            "data" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date()),
                            "origem" to "RODRIGUES_GESTOR_ANDROID_NOTIFICACAO"
                        )
                    )
                )
                val db = FirebaseFirestore.getInstance()
                val ref = db.collection("pedidos").document(orderId)
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(ref)
                    val current = sequenceOf("status", "statusPedido", "statusLoja")
                        .mapNotNull { snapshot.getString(it) }
                        .firstOrNull { it.isNotBlank() }
                        ?.uppercase()
                        .orEmpty()
                    val allowed = setOf("AGUARDANDO_CONFIRMACAO", "RECEBIDO", "PENDENTE", "NOVO", "NOVO_PEDIDO")
                    if (current !in allowed) {
                        throw FirebaseFirestoreException(
                            "Pedido não está mais aguardando confirmação.",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }
                    transaction.update(ref, patch)
                }.addOnSuccessListener {
                    OrderRingService.stop(context)
                    NotificationHelper.cancelOrder(context, orderId)
                }.addOnFailureListener {
                    NotificationHelper.showMessage(
                        context,
                        "Pedido não confirmado",
                        it.message ?: "Abra o Gestor e confira o status do pedido.",
                        orderId
                    )
                }.addOnCompleteListener {
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
