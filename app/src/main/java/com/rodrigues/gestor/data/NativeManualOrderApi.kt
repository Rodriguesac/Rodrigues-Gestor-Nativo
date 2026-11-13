package com.rodrigues.gestor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pedido manual segue o mesmo contrato do Gestor20 web (manual_order).
 * Rascunho local criptografado para sobreviver a perda de conexão sem
 * enviar ações automaticamente. submission_id permanece fixo em tentativas
 * após falhas de rede para evitar duplicação por reenvio ambíguo.
 */
data class ManualOrderItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val quantity: String = "1",
    val unitPrice: String = "0,00",
    val details: String = "",
    val notes: String = ""
)

data class ManualOrderDraft(
    val submissionId: String = UUID.randomUUID().toString(),
    val customerName: String = "",
    val phone: String = "",
    val fulfillment: String = "RETIRADA",
    val paymentMethod: String = "PIX",
    val street: String = "",
    val number: String = "",
    val neighborhood: String = "",
    val complement: String = "",
    val reference: String = "",
    val deliveryFee: String = "0,00",
    val customerNote: String = "",
    val items: List<ManualOrderItem> = emptyList()
) {
    val isDelivery: Boolean get() = fulfillment == "ENTREGA"

    fun validate(): String? {
        if (!Regex("^[0-9a-f-]{36}$").matches(submissionId)) return "Identificação do pedido inválida."
        if (customerName.trim().length < 2) return "Informe o nome do cliente."
        val digits = phone.filter(Char::isDigit)
        if (digits.length !in 10..11) return "Informe telefone com DDD (10 ou 11 números)."
        if (fulfillment !in listOf("ENTREGA", "RETIRADA")) return "Tipo de pedido inválido."
        if (paymentMethod !in listOf("PIX", "DINHEIRO", "CREDITO", "DEBITO")) return "Pagamento inválido."
        if (isDelivery &&
            (street.isBlank() || number.isBlank() || neighborhood.isBlank())) {
            return "Para entrega, informe rua, número e bairro."
        }
        if (items.isEmpty() || items.size > 20) return "Adicione de 1 a 20 produtos."
        for (item in items) {
            if (item.name.trim().isBlank()) return "Há um produto sem nome."
            if (item.name.length > 240) return "Nome de produto muito longo."
            val q = item.quantity.toIntOrNull()
            if (q == null || q !in 1..99) return "Quantidade deve ficar entre 1 e 99."
            if (price(item.unitPrice) == null) return "Confira o valor do produto " + item.name
            if (item.notes.length > 1000) return "Observação do produto muito longa."
        }
        if (price(deliveryFee) == null) return "Confira o valor da taxa de entrega."
        if (customerNote.length > 2000) return "Observação do pedido muito longa."
        return null
    }

    val subtotal: BigDecimal get() = items.fold(BigDecimal.ZERO) { sum, item ->
        val qty = item.quantity.toIntOrNull()?.takeIf { it in 1..99 } ?: 0
        sum + (price(item.unitPrice) ?: BigDecimal.ZERO) * BigDecimal(qty)
    }.setScale(2, RoundingMode.HALF_UP)

    val total: BigDecimal get() = subtotal +
        (if (isDelivery) price(deliveryFee) ?: BigDecimal.ZERO else BigDecimal.ZERO)

    companion object {
        fun price(value: String): BigDecimal? = try {
            val normalized = value.trim().replace(',', '.')
            if (!Regex("^[0-9]{1,5}([.][0-9]{1,2})?$").matches(normalized)) return null
            val amount = BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY)
            amount.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal("10000.00") }
        } catch (_: Throwable) { null }
    }
}

/**
 * Sucesso requer ID e código numérico válidos do servidor.
 * HTTP 200 não basta para limpar um rascunho local.
 */
data class ManualOrderReceipt(
    val orderId: String,
    val orderCode: String,
    val alreadyExists: Boolean
) {
    companion object {
        fun validated(orderId: String, orderCode: String, alreadyExists: Boolean): ManualOrderReceipt {
            require(orderId.matches(Regex(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            ))) { "Servidor não confirmou a identificação do pedido." }
            require(orderCode.matches(Regex("^[0-9]{1,24}$"))) {
                "Servidor não confirmou o código numérico do pedido."
            }
            return ManualOrderReceipt(orderId, orderCode, alreadyExists)
        }
    }
}

object NativeManualOrderApi {
    private const val PREFS = "gestor20_manual_draft_encrypted_v1"
    private const val KEY = "draft_ciphertext"
    private const val KEY_ALIAS = "gestor20_manual_draft_aes_v1"
    private val gson = Gson()

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val current = store.getKey(KEY_ALIAS, null)
        if (current is SecretKey) return current
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }

    fun save(context: Context, draft: ManualOrderDraft) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.iv + cipher.doFinal(gson.toJson(draft).toByteArray(Charsets.UTF_8))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): ManualOrderDraft {
        return try {
            val encoded = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return ManualOrderDraft()
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val decoded = String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            val draft = gson.fromJson(decoded, ManualOrderDraft::class.java) ?: return ManualOrderDraft()
            if (draft.submissionId.isBlank() || draft.items.size > 20) ManualOrderDraft() else draft
        } catch (_: Throwable) { ManualOrderDraft() }
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun submit(draft: ManualOrderDraft,
               onSuccess: (ManualOrderReceipt) -> Unit, onError: (Throwable) -> Unit) {
        val problem = draft.validate()
        if (problem != null) { onError(IllegalArgumentException(problem)); return }
        val products = JSONArray()
        draft.items.forEach { item ->
            products.put(JSONObject()
                .put("name", item.name.trim())
                .put("quantity", item.quantity.toInt())
                .put("unit_price", ManualOrderDraft.price(item.unitPrice)!!.toDouble())
                .put("details", item.details.trim())
                .put("notes", item.notes.trim()))
        }
        val address = JSONObject().put("rua", draft.street.trim())
            .put("numero", draft.number.trim())
            .put("bairro", draft.neighborhood.trim())
            .put("complemento", draft.complement.trim())
            .put("referencia", draft.reference.trim())
        val order = JSONObject()
            .put("submission_id", draft.submissionId)
            .put("customer_name", draft.customerName.trim())
            .put("phone", draft.phone.filter(Char::isDigit))
            .put("fulfillment", draft.fulfillment)
            .put("address", address)
            .put("payment_method", draft.paymentMethod)
            .put("delivery_fee", if (draft.isDelivery)
                ManualOrderDraft.price(draft.deliveryFee)!!.toDouble() else 0.0)
            .put("customer_note", draft.customerNote.trim())
            .put("items", products)
        NativeCatalogApi.execute("manual_order", JSONObject().put("order", order),
            onSuccess = { response ->
                try {
                    val result = response.optJSONObject("result")
                        ?: throw IllegalStateException("Servidor não confirmou a criação do pedido.")
                    onSuccess(ManualOrderReceipt.validated(
                        result.optString("order_id"),
                        result.optString("order_code"),
                        result.optBoolean("already_exists", false)
                    ))
                } catch (error: Throwable) {
                    // Resposta ambígua: manter o rascunho até confirmação.
                    onError(error)
                }
            }, onError = onError)
    }
}
