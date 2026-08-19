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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rodrigues.gestor.MainActivity
import com.rodrigues.gestor.R

object NotificationHelper {
    const val CHANNEL_ORDERS = "pedidos_urgentes_v1"
    const val CHANNEL_SERVICE = "gestor_servico_v1"
    const val CHANNEL_MESSAGES = "mensagens_cliente_v1"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
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
        val messages = NotificationChannel(CHANNEL_MESSAGES, "Mensagens de clientes", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Novas mensagens e solicitações dos clientes"
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(orders, service, messages))
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
}
