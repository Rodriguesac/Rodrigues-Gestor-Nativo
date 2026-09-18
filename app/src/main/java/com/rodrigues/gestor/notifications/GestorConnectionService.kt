package com.rodrigues.gestor.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.canAlert
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.SupabaseOrdersApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GestorConnectionService : Service() {
    private var orderListener: ListenerRegistration? = null
    private var firstSnapshot = true
    private val knownPending = mutableSetOf<String>()
    private val knownStatuses = mutableMapOf<String, String>()
    private var activeRingOrderId = ""

    override fun onCreate() {
        super.onCreate()
        GestorCredentials.load(this)
        NotificationHelper.createChannels(this)
        val savedSummary = DailyOrderSummary.load(this)
        startForeground(
            CONNECTION_NOTIFICATION_ID,
            NotificationHelper.connectionNotification(
                this,
                "Conectando à central de pedidos…",
                savedSummary,
            )
        )
        FloatingPanelController.sync(this, savedSummary)
        FirebaseMessaging.getInstance().token.addOnSuccessListener(DeviceRegistrar::register)
        listenOrders()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (GestorCredentials.pin.length != 6) GestorCredentials.load(this)
        if (orderListener == null) listenOrders()
        FloatingPanelController.sync(this)
        return START_STICKY
    }

    private fun listenOrders() {
        orderListener?.remove()
        orderListener = SupabaseOrdersApi.listenOrders(
            intervalMs = 5_000L,
            onData = { orders -> handleOrders(orders) },
            onError = {
                val saved = DailyOrderSummary.load(this)
                FloatingPanelController.sync(this, saved)
                updateConnectionNotification(
                    "Sem conexão • tentando novamente",
                    saved,
                )
            }
        )
    }

    private fun handleOrders(orders: List<Order>) {
        val currentStatuses = orders.associate { it.id to it.status.uppercase(Locale.ROOT) }

        if (!firstSnapshot) {
            orders.forEach { order ->
                val status = currentStatuses[order.id].orEmpty()
                val previous = knownStatuses[order.id]
                if (previous != null && previous !in CANCELED_STATUSES && status in CANCELED_STATUSES) {
                    val reason = sequenceOf("motivoCancelamento", "detalheCancelamento")
                        .mapNotNull { order.raw[it]?.toString() }
                        .firstOrNull { it.isNotBlank() }
                        ?: "Cancelado pelo cliente"
                    NotificationHelper.showCancellation(
                        this,
                        order.id,
                        order.number,
                        order.clientName,
                        reason
                    )
                }
            }
        }

        val pending = orders
            .filter { it.canAlert() }
            .map {
                PendingOrder(
                    id = it.id,
                    number = it.number,
                    client = it.clientName,
                    createdAt = it.createdMillis
                )
            }
            .sortedBy { if (it.createdAt > 0L) it.createdAt else Long.MAX_VALUE }

        val pendingIds = pending.map { it.id }.toSet()
        if (activeRingOrderId.isNotBlank() && activeRingOrderId !in pendingIds) {
            OrderRingService.stopFor(this, activeRingOrderId)
            NotificationHelper.cancelOrder(this, activeRingOrderId)
            activeRingOrderId = ""
        }
        val target = if (firstSnapshot) {
            pending.firstOrNull()
        } else {
            pending.firstOrNull { it.id !in knownPending }
        }

        firstSnapshot = false
        knownPending.clear()
        knownPending.addAll(pendingIds)
        knownStatuses.clear()
        knownStatuses.putAll(currentStatuses)

        val dailySummary = DailyOrderSummary.fromOrders(orders)
        dailySummary.save(this)
        RodriguesStatusWidget.updateAll(this, dailySummary)
        FloatingPanelController.sync(this, dailySummary)
        updateConnectionNotification(
            "atualizado às ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
            dailySummary,
        )

        val nextRing = when {
            target != null -> target
            activeRingOrderId.isBlank() -> pending.firstOrNull()
            else -> null
        }
        nextRing?.let {
            activeRingOrderId = it.id
            OrderRingService.start(this, it.id, it.number, it.client)
        }
        if (pending.isEmpty()) {
            activeRingOrderId = ""
            OrderRingService.stop(this)
        }
    }

    private fun updateConnectionNotification(detail: String, summary: DailyOrderSummary) {
        try {
            NotificationManagerCompat.from(this).notify(
                CONNECTION_NOTIFICATION_ID,
                NotificationHelper.connectionNotification(this, detail, summary)
            )
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        orderListener?.remove()
        orderListener = null
        FloatingPanelController.hide(this)
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
        private val CANCELED_STATUSES = setOf("CANCELADO", "CANCELADA", "CANCELED", "CANCELLED")

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
