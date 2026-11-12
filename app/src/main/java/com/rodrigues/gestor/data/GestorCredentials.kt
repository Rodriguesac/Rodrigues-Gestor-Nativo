package com.rodrigues.gestor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Compatibilidade provisória com o backend gestor-orders, que ainda exige PIN.
 *
 * O PIN só é digitado na ativação inicial do aparelho. Em repouso fica
 * cifrado com uma chave do Android Keystore, não em texto puro.
 * A autenticação definitiva por sessão renovável será um endpoint separado.
 */
object GestorCredentials {
    private const val PREFS = "rodrigues_gestor_secure"
    private const val KEY_LEGACY = "operator_pin"
    private const val KEY_ENCRYPTED = "operator_credential_v2"
    private const val KEY_ALIAS = "rodrigues_gestor_operator_v2"

    @Volatile var pin: String = ""
        private set

    private var appContext: Context? = null

    fun load(context: Context): String {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_ENCRYPTED, null)
        val migrated = if (encrypted == null) prefs.getString(KEY_LEGACY, null) else null
        pin = try {
            when {
                encrypted != null -> decrypt(encrypted)
                !migrated.isNullOrBlank() -> migrated.filter(Char::isDigit).take(6)
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
        if (pin.length == 6 && encrypted == null) save(context, pin)
        // Não manter um segundo registro desprotegido.
        prefs.edit().remove(KEY_LEGACY).apply()
        return pin
    }

    fun save(context: Context, value: String) {
        require(value.length == 6 && value.all(Char::isDigit)) { "Código de acesso inválido." }
        appContext = context.applicationContext
        val ciphertext = encrypt(value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENCRYPTED, ciphertext).remove(KEY_LEGACY).apply()
        pin = value
    }

    fun clear() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_ENCRYPTED)?.remove(KEY_LEGACY)?.apply()
        pin = ""
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val current = store.getKey(KEY_ALIAS, null)
        if (current is SecretKey) return current
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size > 12) { "Credencial inválida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            .filter(Char::isDigit).take(6)
    }
}
