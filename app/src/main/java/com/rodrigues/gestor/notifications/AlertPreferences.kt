package com.rodrigues.gestor.notifications

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

object AlertPreferences {
    private const val PREFS = "rodrigues_gestor_alerts"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
        if (!value) OrderRingService.stop(context)
    }

    fun vibration(context: Context): Boolean = prefs(context).getBoolean("vibration", true)
    fun setVibration(context: Context, value: Boolean) = prefs(context).edit().putBoolean("vibration", value).apply()

    fun orderSoundUri(context: Context): Uri = prefs(context).getString("order_sound_uri", null)
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun setOrderSoundUri(context: Context, uri: Uri) = prefs(context).edit()
        .putString("order_sound_uri", uri.toString())
        .apply()

    fun orderSoundTitle(context: Context): String = try {
        RingtoneManager.getRingtone(context, orderSoundUri(context))?.getTitle(context)
            ?.takeIf { it.isNotBlank() }
            ?: "Som padrão de notificação"
    } catch (_: Throwable) {
        "Som padrão de notificação"
    }

    fun cancellationAlerts(context: Context): Boolean = prefs(context).getBoolean("cancellation_alerts", true)
    fun setCancellationAlerts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("cancellation_alerts", value).apply()

    fun repeatSeconds(context: Context): Int = prefs(context).getInt("repeat_seconds", 15).coerceIn(5, 60)
    fun setRepeatSeconds(context: Context, value: Int) = prefs(context).edit().putInt("repeat_seconds", value.coerceIn(5, 60)).apply()

    fun maxRingMinutes(context: Context): Int = prefs(context).getInt("max_ring_minutes", 5).coerceIn(1, 10)
    fun setMaxRingMinutes(context: Context, value: Int) = prefs(context).edit().putInt("max_ring_minutes", value.coerceIn(1, 10)).apply()

    fun unansweredMinutes(context: Context): Int = prefs(context).getInt("unanswered_minutes", 2).coerceIn(1, 15)
    fun setUnansweredMinutes(context: Context, value: Int) = prefs(context).edit().putInt("unanswered_minutes", value.coerceIn(1, 15)).apply()

    fun lateYellowMinutes(context: Context): Int = prefs(context).getInt("late_yellow_minutes", 20).coerceIn(5, 120)
    fun setLateYellowMinutes(context: Context, value: Int) = prefs(context).edit().putInt("late_yellow_minutes", value.coerceIn(5, 120)).apply()

    fun lateRedMinutes(context: Context): Int = prefs(context).getInt("late_red_minutes", 35).coerceIn(10, 180)
    fun setLateRedMinutes(context: Context, value: Int) = prefs(context).edit().putInt("late_red_minutes", value.coerceIn(10, 180)).apply()

    fun messageAlerts(context: Context): Boolean = prefs(context).getBoolean("message_alerts", true)
    fun setMessageAlerts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("message_alerts", value).apply()

    fun changeAlerts(context: Context): Boolean = prefs(context).getBoolean("change_alerts", true)
    fun setChangeAlerts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("change_alerts", value).apply()

    fun driverAlerts(context: Context): Boolean = prefs(context).getBoolean("driver_alerts", true)
    fun setDriverAlerts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("driver_alerts", value).apply()

    fun customerTrackingDefault(context: Context): Boolean = prefs(context).getBoolean("customer_tracking_default", true)
    fun setCustomerTrackingDefault(context: Context, value: Boolean) = prefs(context).edit()
        .putBoolean("customer_tracking_default", value)
        .apply()

    fun paymentAlerts(context: Context): Boolean = prefs(context).getBoolean("payment_alerts", true)
    fun setPaymentAlerts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("payment_alerts", value).apply()

    fun compactCards(context: Context): Boolean = prefs(context).getBoolean("compact_cards", false)
    fun setCompactCards(context: Context, value: Boolean) = prefs(context).edit().putBoolean("compact_cards", value).apply()

    fun autoPrintOnAccept(context: Context): Boolean = prefs(context).getBoolean("auto_print_accept", false)
    fun setAutoPrintOnAccept(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto_print_accept", value).apply()

    fun printCopies(context: Context): Int = prefs(context).getInt("print_copies", 1).coerceIn(1, 3)
    fun setPrintCopies(context: Context, value: Int) = prefs(context).edit().putInt("print_copies", value.coerceIn(1, 3)).apply()

    fun paperWidth(context: Context): Int = prefs(context).getInt("paper_width", 80).let { if (it == 58) 58 else 80 }
    fun setPaperWidth(context: Context, value: Int) = prefs(context).edit().putInt("paper_width", if (value == 58) 58 else 80).apply()
}
