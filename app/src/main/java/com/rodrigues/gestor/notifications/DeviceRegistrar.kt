package com.rodrigues.gestor.notifications

import android.os.Build
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

object DeviceRegistrar {
    fun register(token: String) {
        if (token.isBlank()) return
        val id = sha256(token).take(48)
        FirebaseFirestore.getInstance().collection("gestor_dispositivos").document(id).set(
            mapOf(
                "token" to token,
                "plataforma" to "ANDROID",
                "app" to "RODRIGUES_GESTOR",
                "package" to "com.rodrigues.gestor",
                "modelo" to (Build.MANUFACTURER + " " + Build.MODEL),
                "sdk" to Build.VERSION.SDK_INT,
                "ativo" to true,
                "atualizadoEm" to FieldValue.serverTimestamp()
            )
        )
        FirebaseMessaging.getInstance().subscribeToTopic("gestor-pedidos")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
