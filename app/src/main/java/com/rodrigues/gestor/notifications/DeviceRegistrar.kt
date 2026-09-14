package com.rodrigues.gestor.notifications

import android.os.Build
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

object DeviceRegistrar {
    private const val TOPIC = "gestor-pedidos"

    fun register(token: String) {
        if (token.isBlank()) return
        val id = sha256(token).take(48)
        val ref = FirebaseFirestore.getInstance().collection("gestor_dispositivos").document(id)
        val base = mapOf(
            "token" to token,
            "plataforma" to "ANDROID",
            "app" to "RODRIGUES_GESTOR",
            "package" to "com.rodrigues.gestor",
            "modelo" to (Build.MANUFACTURER + " " + Build.MODEL),
            "sdk" to Build.VERSION.SDK_INT,
            "ativo" to true,
            "fcmDisponivel" to true,
            "atualizadoEm" to FieldValue.serverTimestamp()
        )
        ref.set(base, SetOptions.merge())

        // O tópico é redundância. O backend também envia diretamente aos tokens registrados,
        // então uma inscrição atrasada no tópico não faz o pedido se perder.
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC).addOnCompleteListener { task ->
            ref.set(
                mapOf(
                    "topicoGestorPedidos" to task.isSuccessful,
                    "topicoAtualizadoEm" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
