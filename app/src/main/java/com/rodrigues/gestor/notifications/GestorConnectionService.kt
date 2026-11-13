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
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.OfflineOrderStore
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
    private val persistentCache by lazy { OfflineOrderStore(this) }

    override fun onCreate() {
        super.onCreate()
        GestorCredentials.load(this)
        NotificationHelper.createChannels(this)
        startForeground(
            CONNECTION_NOTIFICATION_ID,
            NotificationHelper.connectionNotification(this, "Conectando à central de pedidos…")
        )
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            DeviceRegistrar.register(this, token)
        }
        listenOrders()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (GestorCredentials.pin.length != 6) GestorCredentials.load(this)
        if (orderListener == null) listenOrders()
        return START_STICKY
    }

    private fun listenOrders() {
        orderListener?.remove()
        orderListener = SupabaseOrdersApi.listenOrders(
            intervalMs = 5_000L,
            onData = { orders -> handleOrders(orders) },
            onError = {
                updateConnectionNotification("Sem conexão • tentando novamente", knownPending.size)
            }
        )
    }

    private fun handleOrders(orders: List<Order>) {
        // Salva o pedido ANTES de acionar o toque. Assim ele está disponível
        // mesmo se a conexão cair depois de aparecer a notificação.
        try { persistentCache.storeOrders(orders) } catch (_: Throwable) { }
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

        val now = System.currentTimeMillis()
        val pending = orders
            .filter {
                it.status.uppercase(Locale.ROOT) in StatusGroups.NEW &&
                    it.createdMillis > 0L &&
                    now - it.createdMillis <= 15 * 60_000L
            }
            .map {
                PendingOrder(
                    id = it.id,
                    number = it.number,
                    client = it.clientName,
                    createdAt = it.createdMillis
                )
            }
            .sortedBy { if (it.createdAt > 0L) it.createdAt else Long.MAX_VALUE }

        // Alertas locais com deduplicação persistida: funcionam mesmo quando
        // nenhuma mensagem push específica de atraso foi enviada.
        if (AlertPreferences.enabled(this)) {
            orders.forEach { order ->
                val ageMinutes = if (order.createdMillis > 0L)
                    ((now - order.createdMillis).coerceAtLeast(0L) / 60_000L).toInt()
                else -1
                if (ageMinutes !in 0..180) return@forEach
                val status = order.status.uppercase(Locale.ROOT)
                if (status in StatusGroups.NEW && ageMinutes >= 3 &&
                    firstTimedAlert("pending", order.id)) {
                    NotificationHelper.showPendingAcceptance(this, order.id, order.number)
                }
                val late = (status in StatusGroups.NEW && ageMinutes >= 5) ||
                    ((status in StatusGroups.CONFIRMED || status in StatusGroups.PREPARING) && ageMinutes >= 30)
                if (late && firstTimedAlert("late", order.id)) {
                    NotificationHelper.showLate(this, order.id, order.number, ageMinutes)
                }
            }
        }

        val pendingIds = pending.map { it.id }.toSet()
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

        updateConnectionNotification(
            "Supabase atualizado às ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
            pending.size
        )

        val nextRing = when {
            target != null -> target
            activeRingOrderId.isNotBlank() && activeRingOrderId !in pendingIds -> pending.firstOrNull()
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

    private fun updateConnectionNotification(detail: String, pendingCount: Int) {
        try {
            NotificationManagerCompat.from(this).notify(
                CONNECTION_NOTIFICATION_ID,
                NotificationHelper.connectionNotification(this, detail, pendingCount)
            )
        } catch (_: SecurityException) {
        }
    }

    private fun firstTimedAlert(category: String, orderId: String): Boolean {
        if (orderId.isBlank()) return false
        val prefs = getSharedPreferences("gestor_timed_alerts_v1", MODE_PRIVATE)
        val key = "$category:$orderId"
        if (prefs.contains(key)) return false
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        return true
    }

    override fun onDestroy() {
        orderListener?.remove()
        orderListener = null
        try { persistentCache.close() } catch (_: Throwable) { }
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
