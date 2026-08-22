package com.rodrigues.gestor.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rodrigues.gestor.MainActivity
import com.rodrigues.gestor.R

object NotificationHelper {
    const val CHANNEL_ORDERS = "pedidos_urgentes_v3"
    const val CHANNEL_SERVICE = "gestor_servico_v1"
    const val CHANNEL_CONNECTION = "gestor_conectado_v1"
    const val CHANNEL_MESSAGES = "mensagens_cliente_v1"
    const val CHANNEL_CANCELLATIONS = "pedidos_cancelados_v1"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val orders = NotificationChannel(CHANNEL_ORDERS, "Pedidos urgentes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Novo pedido e ações imediatas da loja"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 550, 180, 550)
            setSound(ringtoneUri, attrs)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val service = NotificationChannel(CHANNEL_SERVICE, "Gestor ativo", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantém o toque de novo pedido ativo enquanto necessário"
            setSound(null, null)
            enableVibration(false)
        }
        val connection = NotificationChannel(CHANNEL_CONNECTION, "Gestor conectado", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mantém a central de pedidos conectada mesmo fora da tela"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val messages = NotificationChannel(CHANNEL_MESSAGES, "Mensagens de clientes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Novas mensagens e solicitações dos clientes"
            enableVibration(true)
        }
        val cancellations = NotificationChannel(CHANNEL_CANCELLATIONS, "Pedidos cancelados", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Aviso curto quando um pedido é cancelado"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 240, 120, 420)
            setSound(ringtoneUri, attrs)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(orders, service, connection, messages, cancellations))
    }

    fun connectionNotification(
        context: Context,
        detail: String = "Monitorando novos pedidos",
        pendingCount: Int = 0,
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            4601,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (pendingCount > 0) "$detail • $pendingCount aguardando confirmação" else detail
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rodrigues Gestor conectado")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .addAction(0, "ABRIR GESTOR", pending)
            .build()
    }

    fun orderNotification(
        context: Context,
        orderId: String,
        number: String,
        clientName: String,
        foregroundService: Boolean = false,
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ORDER_ID, orderId)
        }
        val openPending = PendingIntent.getActivity(
            context,
            orderId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, OrderActionReceiver::class.java).apply {
            action = OrderActionReceiver.ACTION_ACCEPT
            putExtra(OrderActionReceiver.EXTRA_ORDER_ID, orderId)
        }
        val acceptPending = PendingIntent.getBroadcast(
            context,
            orderId.hashCode() + 100_000,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(context, OrderActionReceiver::class.java).apply {
            action = OrderActionReceiver.ACTION_SILENCE
            putExtra(OrderActionReceiver.EXTRA_ORDER_ID, orderId)
        }
        val silencePending = PendingIntent.getBroadcast(
            context,
            orderId.hashCode() + 200_000,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channel = if (foregroundService) CHANNEL_SERVICE else CHANNEL_ORDERS
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NOVO PEDIDO #$number")
            .setContentText(clientName.ifBlank { "Abra para ver os itens" })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$clientName • Toque para abrir o pedido e iniciar o preparo."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(!foregroundService)
            .setOngoing(foregroundService)
            .setContentIntent(openPending)
            .addAction(0, "ACEITAR", acceptPending)
            .addAction(0, "SILENCIAR", silencePending)
            .build()
    }

    fun cancelOrder(context: Context, orderId: String) {
        NotificationManagerCompat.from(context).cancel(orderId.hashCode())
    }

    fun showOrderOnce(context: Context, orderId: String, number: String, clientName: String) {
        createChannels(context)
        try {
            NotificationManagerCompat.from(context).notify(orderId.hashCode(), orderNotification(context, orderId, number, clientName))
        } catch (_: SecurityException) {
        }
    }

    fun showMessage(context: Context, title: String, body: String, orderId: String = "") {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (orderId.isNotBlank()) putExtra(MainActivity.EXTRA_ORDER_ID, orderId)
        }
        val pending = PendingIntent.getActivity(
            context,
            ("msg:$orderId:$title").hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("message:$orderId:$title").hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    fun showCancellation(
        context: Context,
        orderId: String,
        number: String,
        clientName: String,
        reason: String = "",
    ) {
        if (!AlertPreferences.cancellationAlerts(context) || isCancellationDuplicate(context, orderId)) return
        createChannels(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (orderId.isNotBlank()) putExtra(MainActivity.EXTRA_ORDER_ID, orderId)
        }
        val pending = PendingIntent.getActivity(
            context,
            ("cancel:$orderId").hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val detail = reason.ifBlank { clientName.ifBlank { "Abra o Gestor para conferir." } }
        val notification = NotificationCompat.Builder(context, CHANNEL_CANCELLATIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PEDIDO CANCELADO #${number.ifBlank { orderId.takeLast(6).uppercase() }}")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .addAction(0, "VER PEDIDO", pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("cancel:$orderId").hashCode(), notification)
        } catch (_: SecurityException) {
        }
        if (AlertPreferences.vibration(context) && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            vibrateCancellation(context)
        }
    }

    fun markCancellationHandled(context: Context, orderId: String) {
        if (orderId.isBlank()) return
        context.getSharedPreferences("cancel_alert_dedup", Context.MODE_PRIVATE)
            .edit()
            .putLong(orderId, System.currentTimeMillis())
            .apply()
    }

    private fun isCancellationDuplicate(context: Context, orderId: String): Boolean {
        if (orderId.isBlank()) return false
        val prefs = context.getSharedPreferences("cancel_alert_dedup", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(orderId, 0L)
        if (last > 0L && now - last < 60_000L) return true
        prefs.edit().putLong(orderId, now).apply()
        return false
    }

    private fun vibrateCancellation(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 240, 120, 420)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
