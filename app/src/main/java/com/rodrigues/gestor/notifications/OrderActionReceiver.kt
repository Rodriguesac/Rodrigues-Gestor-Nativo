package com.rodrigues.gestor.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Notification actions use Supabase canonical orders, not the old Firestore mirror. */
class OrderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SILENCE -> OrderRingService.stop(context)
            ACTION_ACCEPT -> {
                val id = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
                if (id.isBlank()) return
                val request = Intent(context, GestorConnectionService::class.java).apply {
                    action = GestorConnectionService.ACTION_ACCEPT_FROM_NOTIFICATION
                    putExtra(EXTRA_ORDER_ID, id)
                }
                try {
                    ContextCompat.startForegroundService(context, request)
                } catch (_: Exception) {
                    NotificationHelper.showMessage(context,
                        "Abra o Gestor para aceitar",
                        "O Android impediu a confirmação em segundo plano. Abra o aplicativo para confirmar.",
                        id)
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
