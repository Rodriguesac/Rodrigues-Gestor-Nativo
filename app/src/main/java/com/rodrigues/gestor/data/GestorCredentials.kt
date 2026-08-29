package com.rodrigues.gestor.data

import android.content.Context

object GestorCredentials {
    private const val PREFS = "rodrigues_gestor_secure"
    private const val KEY_PIN = "operator_pin"

    @Volatile
    var pin: String = ""
        private set

    private var appContext: Context? = null

    fun load(context: Context): String {
        appContext = context.applicationContext
        pin = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN, "")
            .orEmpty()
            .filter { it.isDigit() }
            .take(6)
        return pin
    }

    fun save(context: Context, value: String) {
        appContext = context.applicationContext
        pin = value.filter { it.isDigit() }.take(6)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PIN, pin)
            .apply()
    }

    fun clear() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_PIN)
            ?.apply()
        pin = ""
    }
}
