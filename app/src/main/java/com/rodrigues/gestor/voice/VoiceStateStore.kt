package com.rodrigues.gestor.voice

import org.json.JSONArray
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

data class VoiceOrderItem(
    val name: String,
    val quantity: Int,
    val details: List<String>,
)

data class VoiceOrder(
    val id: String,
    val number: String,
    val status: String,
    val clientName: String,
    val type: String,
    val createdMillis: Long,
    val items: List<VoiceOrderItem>,
) {
    val pickup: Boolean get() = type.uppercase(Locale.ROOT) == "RETIRADA"
}

object VoiceStateStore {
    @Volatile private var orders: List<VoiceOrder> = emptyList()
    @Volatile private var activeOrderId: String? = null
    @Volatile var lastSyncMillis: Long = 0L
        private set

    private val terminal = setOf(
        "CONCLUIDO", "CONCLUÍDO", "FINALIZADO", "ENTREGUE", "RETIRADO", "CANCELADO", "CANCELADA"
    )

    fun syncOrders(json: String) {
        try {
            val array = JSONArray(json)
            val parsed = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val id = row.optString("id").trim()
                    if (id.isBlank()) continue
                    val itemArray = row.optJSONArray("items") ?: JSONArray()
                    val items = buildList {
                        for (j in 0 until itemArray.length()) {
                            val item = itemArray.optJSONObject(j) ?: continue
                            val detailsArray = item.optJSONArray("details") ?: JSONArray()
                            val details = buildList {
                                for (k in 0 until detailsArray.length()) {
                                    detailsArray.optString(k).trim().takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }
                            add(
                                VoiceOrderItem(
                                    name = item.optString("name", "Item").trim(),
                                    quantity = item.optInt("qty", 1).coerceAtLeast(1),
                                    details = details,
                                )
                            )
                        }
                    }
                    val created = runCatching {
                        Instant.parse(row.optString("created")).toEpochMilli()
                    }.getOrDefault(0L)
                    add(
                        VoiceOrder(
                            id = id,
                            number = row.optString("number").trim(),
                            status = row.optString("status").trim().uppercase(Locale.ROOT),
                            clientName = row.optString("clientName", "Cliente").trim(),
                            type = row.optString("type", "ENTREGA").trim().uppercase(Locale.ROOT),
                            createdMillis = created,
                            items = items,
                        )
                    )
                }
            }
            orders = parsed
            lastSyncMillis = System.currentTimeMillis()
            val active = activeOrderId
            if (active != null && parsed.none { it.id == active }) activeOrderId = null
        } catch (_: Throwable) {
            // Mantém o último snapshot válido.
        }
    }

    fun setActiveOrder(id: String?) {
        activeOrderId = id?.trim()?.takeIf { it.isNotBlank() }
    }

    fun activeOrder(): VoiceOrder? =
        activeOrderId?.let { id -> orders.firstOrNull { it.id == id } }

    fun activeOrders(): List<VoiceOrder> =
        orders.filter { it.status !in terminal }
            .sortedWith(compareBy<VoiceOrder> { if (it.createdMillis > 0) it.createdMillis else Long.MAX_VALUE }.thenBy { it.number })

    fun nextOrder(): VoiceOrder? {
        val activeId = activeOrderId
        val list = activeOrders()
        if (activeId == null) return list.firstOrNull()
        val index = list.indexOfFirst { it.id == activeId }
        return if (index >= 0 && index + 1 < list.size) list[index + 1] else list.firstOrNull { it.id != activeId } ?: list.firstOrNull()
    }

    fun findTarget(spoken: String): VoiceOrder? {
        val normalized = normalize(spoken)
        if (Regex("\\b(esse|este|atual|aberto|selecionado)\\b").containsMatchIn(normalized)) {
            activeOrder()?.let { return it }
        }

        val digits = Regex("\\d{2,}").findAll(normalized).map { it.value.trimStart('0') }.filter { it.isNotBlank() }.toList()
        for (candidate in digits) {
            orders.firstOrNull {
                val orderDigits = it.number.filter(Char::isDigit).trimStart('0')
                orderDigits == candidate || orderDigits.endsWith(candidate)
            }?.let { return it }
        }

        val usefulWords = normalized.split(' ')
            .filter { it.length >= 3 && it !in setOf("pedido", "cliente", "avancar", "despachar", "pronto", "finalizar", "aceitar", "preparo") }
        if (usefulWords.isNotEmpty()) {
            orders.firstOrNull { order ->
                val client = normalize(order.clientName)
                usefulWords.any { word -> client.contains(word) }
            }?.let { return it }
        }
        return null
    }

    fun defaultTarget(spoken: String): VoiceOrder? =
        findTarget(spoken) ?: activeOrder() ?: activeOrders().firstOrNull()

    fun describe(order: VoiceOrder): String {
        if (order.items.isEmpty()) return "O pedido " + order.number + " não tem itens detalhados."
        val parts = order.items.flatMap { item ->
            val header = if (item.quantity > 1) item.quantity.toString() + " " + item.name else item.name
            if (item.details.isEmpty()) listOf(header) else listOf(header) + item.details
        }
        return "Pedido " + order.number + ". " + parts.take(18).joinToString(". ") +
            if (parts.size > 18) ". Há mais itens na comanda." else "."
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
