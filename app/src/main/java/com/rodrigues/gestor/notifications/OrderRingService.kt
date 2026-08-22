package com.rodrigues.gestor.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat

class OrderRingService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var repeatMs = 15_000L
    private var maxRunMs = 5 * 60_000L
    private var currentOrderId = ""

    private val stopBurst = Runnable {
        try { ringtone?.stop() } catch (_: Throwable) { }
        vibrator?.cancel()
    }

    private val ringBurst = object : Runnable {
        override fun run() {
            if (!AlertPreferences.enabled(this@OrderRingService) || System.currentTimeMillis() - startedAt >= maxRunMs) {
                stopSelf()
                return
            }
            try {
                ringtone?.stop()
                ringtone?.play()
            } catch (_: Throwable) { }
            if (AlertPreferences.vibration(this@OrderRingService)) vibrateBurst()
            handler.removeCallbacks(stopBurst)
            handler.postDelayed(stopBurst, 3_200L)
            handler.postDelayed(this, repeatMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
        }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val orderId = intent?.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        val number = intent?.getStringExtra(EXTRA_NUMBER).orEmpty().ifBlank { orderId.takeLast(6).uppercase() }
        val client = intent?.getStringExtra(EXTRA_CLIENT).orEmpty()
        if (orderId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (orderId == currentOrderId && startedAt > 0L && System.currentTimeMillis() - startedAt < maxRunMs) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                NotificationHelper.orderNotification(this, orderId, number, client, foregroundService = true)
            )
            return START_NOT_STICKY
        }
        currentOrderId = orderId

        startForeground(
            FOREGROUND_NOTIFICATION_ID,
            NotificationHelper.orderNotification(this, orderId, number, client, foregroundService = true)
        )

        repeatMs = AlertPreferences.repeatSeconds(this).toLong() * 1000L
        maxRunMs = AlertPreferences.maxRingMinutes(this).toLong() * 60_000L
        startedAt = System.currentTimeMillis()

        wakeLock?.let { if (it.isHeld) it.release() }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RodriguesGestor:NewOrder").apply {
            setReferenceCounted(false)
            acquire(maxRunMs + 60_000L)
        }

        handler.removeCallbacksAndMessages(null)
        handler.post(ringBurst)
        return START_NOT_STICKY
    }

    private fun vibrateBurst() {
        val pattern = longArrayOf(0, 500, 180, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { ringtone?.stop() } catch (_: Throwable) { }
        vibrator?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        currentOrderId = ""
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rodrigues.gestor.STOP_ORDER_RING"
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_NUMBER = "order_number"
        const val EXTRA_CLIENT = "client_name"
        const val FOREGROUND_NOTIFICATION_ID = 9911

        fun start(context: Context, orderId: String, number: String, clientName: String) {
            if (!AlertPreferences.enabled(context)) return
            val intent = Intent(context, OrderRingService::class.java).apply {
                putExtra(EXTRA_ORDER_ID, orderId)
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_CLIENT, clientName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
                NotificationHelper.showOrderOnce(context, orderId, number, clientName)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderRingService::class.java))
        }
    }
}
