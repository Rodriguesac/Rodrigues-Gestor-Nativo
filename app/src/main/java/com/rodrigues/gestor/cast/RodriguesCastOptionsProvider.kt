package com.rodrigues.gestor.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

object RodriguesCastConfig {
    const val PREFS = "rodrigues_cast"
    const val KEY_APP_ID = "receiver_app_id"
    const val NAMESPACE = "urn:x-cast:com.rodrigues.cozinha"

    fun receiverAppId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APP_ID, "")
            .orEmpty()
            .trim()
            .uppercase()

    fun saveReceiverAppId(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_ID, value.trim().uppercase())
            .apply()
    }
}

class RodriguesCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val configured = RodriguesCastConfig.receiverAppId(context)
        val appId = configured.ifBlank { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID }

        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setSupportedNamespaces(listOf(RodriguesCastConfig.NAMESPACE))
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
