package com.rodrigues.gestor.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GestorConnectionService : Service() {
    private var orderListener: ListenerRegistration? = null
    private var firstSnapshot = true
    private val knownPending = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        startForeground(
            CONNECTION_NOTIFICATION_ID,
            NotificationHelper.connectionNotification(this, "Conectando à central de pedidos…")
        )
        FirebaseMessaging.getInstance().token.addOnSuccessListener(DeviceRegistrar::register)
        listenOrders()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (orderListener == null) listenOrders()
        return START_STICKY
    }

    private fun listenOrders() {
        orderListener?.remove()
        orderListener = FirebaseFirestore.getInstance().collection("pedidos")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    updateConnectionNotification("Sem conexão • tentando novamente", knownPending.size)
                    return@addSnapshotListener
                }

                val pending = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val status = sequenceOf("status", "statusPedido", "statusLoja")
                        .mapNotNull { doc.getString(it) }
                        .firstOrNull { it.isNotBlank() }
                        ?.uppercase(Locale.ROOT)
                        .orEmpty()
                    if (status !in NEW_STATUSES) return@mapNotNull null
                    PendingOrder(
                        id = doc.id,
                        number = sequenceOf("numeroPedido", "codigoPedido", "numero")
                            .mapNotNull { doc.getString(it) }
                            .firstOrNull { it.isNotBlank() }
                            ?: doc.id.takeLast(6).uppercase(Locale.ROOT),
                        client = doc.getString("clienteNome")
                            ?: doc.getString("nomeCliente")
                            ?: (doc.get("cliente") as? Map<*, *>)?.get("nome")?.toString()
                            ?: "Cliente",
                        createdAt = timestampMillis(doc.get("createdAt") ?: doc.get("criadoEm"))
                    )
                }.sortedBy { if (it.createdAt > 0L) it.createdAt else Long.MAX_VALUE }

                val pendingIds = pending.map { it.id }.toSet()
                val target = if (firstSnapshot) {
                    pending.firstOrNull()
                } else {
                    pending.firstOrNull { it.id !in knownPending }
                }
                firstSnapshot = false
                knownPending.clear()
                knownPending.addAll(pendingIds)

                updateConnectionNotification(
                    "Última atualização às ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
                    pending.size
                )

                target?.let {
                    OrderRingService.start(this, it.id, it.number, it.client)
                }
                if (pending.isEmpty()) OrderRingService.stop(this)
            }
    }

    private fun updateConnectionNotification(detail: String, pendingCount: Int) {
        try {
            NotificationManagerCompat.from(this).notify(
                CONNECTION_NOTIFICATION_ID,
                NotificationHelper.connectionNotification(this, detail, pendingCount)
            )
        } catch (_: SecurityException) {
        }
    }

    private fun timestampMillis(value: Any?): Long = when (value) {
        is Timestamp -> value.toDate().time
        is Date -> value.time
        is Number -> value.toLong()
        else -> 0L
    }

    override fun onDestroy() {
        orderListener?.remove()
        orderListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class PendingOrder(
        val id: String,
        val number: String,
        val client: String,
        val createdAt: Long,
    )

    companion object {
        const val CONNECTION_NOTIFICATION_ID = 9901
        private val NEW_STATUSES = setOf(
            "AGUARDANDO_CONFIRMACAO", "RECEBIDO", "PENDENTE", "NOVO", "NOVO_PEDIDO"
        )

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GestorConnectionService::class.java)
                )
            } catch (_: Throwable) {
            }
        }
    }
}
