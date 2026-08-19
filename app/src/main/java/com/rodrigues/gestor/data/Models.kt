package com.rodrigues.gestor.data

import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PaymentInfo(
    val form: String = "—",
    val status: String = "",
    val needsMachine: Boolean = false,
    val needsChange: Boolean = false,
    val changeFor: Double = 0.0,
    val amountToCollect: Double = 0.0,
)

data class OrderItem(
    val name: String,
    val quantity: Int = 1,
    val price: Double = 0.0,
    val details: List<String> = emptyList(),
)

data class Order(
    val id: String,
    val number: String,
    val status: String,
    val clientName: String,
    val clientUid: String,
    val phone: String,
    val address: String,
    val neighborhood: String,
    val items: List<OrderItem>,
    val observation: String,
    val total: Double,
    val subtotal: Double,
    val freight: Double,
    val discount: Double,
    val createdMillis: Long,
    val payment: PaymentInfo,
    val pickup: Boolean,
    val raw: Map<String, Any?>,
)

data class Driver(
    val id: String,
    val name: String,
    val photoUrl: String,
    val online: Boolean,
    val approved: Boolean,
    val busy: Boolean,
    val acceptsOffers: Boolean,
    val status: String,
    val canReceiveComplement: Boolean = false,
    val openRouteId: String = "",
    val batteryLevel: Int? = null,
    val raw: Map<String, Any?>,
) {
    val available: Boolean get() = online && approved && !busy && acceptsOffers
    val dispatchable: Boolean get() = available || (online && approved && canReceiveComplement && openRouteId.isNotBlank())
}

data class ChatMessage(
    val sender: String,
    val text: String,
    val time: String,
    val timestamp: Long,
)

data class OrderChat(
    val id: String,
    val orderId: String,
    val messages: List<ChatMessage>,
)

object StatusGroups {
    val NEW = setOf("RECEBIDO", "PENDENTE", "NOVO", "NOVO_PEDIDO")
    val PREPARING = setOf("CONFIRMADO", "FILA", "EM_PREPARO", "PREPARANDO", "ACEITO")
    val READY = setOf("PRONTO")
    val WAITING_DRIVER = setOf("BUSCANDO_ENTREGADOR", "AGUARDANDO_ENTREGADOR", "AGUARDANDO_DECISAO_GESTOR", "OFERTA_COMPLEMENTO_ROTA")
    val TO_STORE = setOf("A_CAMINHO_LOJA", "ENTREGADOR_A_CAMINHO_LOJA", "ACEITA", "PICKUP")
    val AT_STORE = setOf("COLETANDO", "ENTREGADOR_CHEGOU_LOJA")
    val TO_CUSTOMER = setOf("SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA")
    val AT_CUSTOMER = setOf("ENTREGADOR_NO_LOCAL", "ENTREGADOR_CHEGOU_CLIENTE", "NO_CLIENTE")
    val DELIVERY = WAITING_DRIVER + TO_STORE + AT_STORE + TO_CUSTOMER + AT_CUSTOMER
    val DONE = setOf("ENTREGUE", "CONCLUIDO", "CONCLUÍDO", "FINALIZADO", "RETIRADO")
    val CANCELED = setOf("CANCELADO", "CANCELADA")

    fun label(status: String): String {
        val s = status.uppercase(Locale.ROOT)
        return when {
            s in NEW -> "Novo"
            s in PREPARING -> "Em preparo"
            s in READY -> "Pronto"
            s in WAITING_DRIVER -> "Aguardando entregador"
            s in TO_STORE -> "A caminho da loja"
            s in AT_STORE -> "Na loja / retirada"
            s in TO_CUSTOMER -> "Em rota ao cliente"
            s in AT_CUSTOMER -> "No cliente"
            s in DONE -> "Finalizado"
            s in CANCELED -> "Cancelado"
            else -> s.replace('_', ' ').ifBlank { "—" }
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun mapValue(map: Map<String, Any?>, key: String): Map<String, Any?>? = map[key] as? Map<String, Any?>

fun firstValue(map: Map<String, Any?>, vararg keys: String): Any? {
    for (key in keys) {
        val value = map[key]
        if (value != null && value.toString().isNotBlank()) return value
    }
    return null
}

fun firstString(map: Map<String, Any?>, vararg keys: String, default: String = ""): String =
    firstValue(map, *keys)?.toString()?.takeIf { it.isNotBlank() } ?: default

fun asDouble(value: Any?): Double = when (value) {
    is Number -> value.toDouble()
    is String -> value.replace(',', '.').toDoubleOrNull() ?: 0.0
    else -> 0.0
}

fun asLongTime(value: Any?): Long = when (value) {
    is Timestamp -> value.toDate().time
    is Date -> value.time
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

fun normalizeOrder(id: String, raw: Map<String, Any?>): Order {
    val client = mapValue(raw, "cliente") ?: emptyMap()
    val delivery = mapValue(raw, "entrega") ?: emptyMap()
    val addressMap = mapValue(raw, "endereco") ?: mapValue(delivery, "endereco") ?: emptyMap()
    val paymentMap = mapValue(raw, "pagamento") ?: emptyMap()

    val rawItems = (firstValue(raw, "itens", "items", "produtos", "carrinho") as? List<*>) ?: emptyList<Any?>()
    val items = rawItems.mapNotNull { any ->
        val item = any as? Map<String, Any?> ?: return@mapNotNull null
        val detailsMap = mapValue(item, "detalhes") ?: emptyMap()
        val name = firstString(item, "titulo", "nome", "produtoNome", "baseNome", default = "Item")
            .ifBlank { firstString(detailsMap, "baseNome", default = "Item") }
        val qty = asDouble(firstValue(item, "quantidade", "qtd", "quantity")).toInt().coerceAtLeast(1)
        val price = asDouble(firstValue(item, "total", "precoTotal", "preco", "valor"))
        val detailNames = linkedSetOf<String>()
        fun collect(v: Any?) {
            when (v) {
                is String -> if (v.isNotBlank()) detailNames += v.trim()
                is List<*> -> v.forEach { x ->
                    when (x) {
                        is String -> if (x.isNotBlank()) detailNames += x.trim()
                        is Map<*, *> -> {
                            val m = x as Map<String, Any?>
                            val n = firstString(m, "nome", "titulo", "label")
                            if (n.isNotBlank()) detailNames += n.trim()
                        }
                    }
                }
            }
        }
        collect(firstValue(item, "linhas", "linhasMontagem", "linhasIncluido"))
        collect(firstValue(detailsMap, "linhas", "linhasMontagem", "linhasIncluido"))
        collect(detailsMap["cobertura_detalhes"])
        listOf("acompanhamentos_detalhes", "adicionais_detalhes", "acompanhamentos", "adicionais", "extras")
            .forEach { k -> collect(detailsMap[k] ?: item[k]) }
        OrderItem(name = name, quantity = qty, price = price, details = detailNames.toList())
    }

    val status = firstString(raw, "status", "statusPedido", "statusLoja", "statusNormalizado", default = "RECEBIDO")
        .uppercase(Locale.ROOT)
    val number = firstString(raw, "numeroPedido", "codigoPedido", "numero", "codigoCurto", default = id.takeLast(6).uppercase())
    val clientName = firstString(client, "nome").ifBlank {
        firstString(raw, "clienteNome", "nomeCliente", "nome", default = "Cliente")
    }
    val clientUid = firstString(client, "uid").ifBlank { firstString(raw, "clienteUid", "uidCliente") }
    val phone = firstString(client, "telefone").ifBlank { firstString(raw, "clienteTelefone", "telefone") }

    val address = firstString(addressMap, "completo", "texto").ifBlank {
        val street = firstString(addressMap, "rua")
        val num = firstString(addressMap, "numero")
        val bairro = firstString(addressMap, "bairro")
        listOf(street, num, bairro).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { firstString(raw, "enderecoEntrega", "enderecoCliente", default = "Endereço não informado") }
    }
    val neighborhood = firstString(addressMap, "bairro").ifBlank { firstString(raw, "clienteBairro") }

    val paymentForm = firstString(paymentMap, "forma", "metodo").ifBlank {
        val p = raw["pagamento"]
        if (p is String) p else firstString(raw, "formaPagamento", default = "—")
    }
    val payment = PaymentInfo(
        form = paymentForm,
        status = firstString(paymentMap, "status").ifBlank { firstString(raw, "pagamentoStatus") },
        needsMachine = paymentMap["precisaMaquininha"] == true || raw["precisaMaquininha"] == true,
        needsChange = paymentMap["precisaTroco"] == true || raw["precisaTroco"] == true,
        changeFor = asDouble(paymentMap["trocoPara"] ?: raw["trocoPara"]),
        amountToCollect = asDouble(paymentMap["valorReceberCliente"] ?: raw["valorReceberCliente"]),
    )

    val type = firstString(raw, "tipoPedido", "tipoEntrega", "modalidade").uppercase(Locale.ROOT)
    val mode = firstString(raw, "entregaModo", "tipoEntregaOperacional", "tipoEntrega").ifBlank {
        firstString(delivery, "modo")
    }.uppercase(Locale.ROOT)
    val pickup = mode == "RETIRADA" || type in setOf("RETIRADA", "BALCAO", "BALCÃO", "PICKUP")

    return Order(
        id = id,
        number = number,
        status = status,
        clientName = clientName,
        clientUid = clientUid,
        phone = phone,
        address = address,
        neighborhood = neighborhood,
        items = items,
        observation = firstString(raw, "observacao", "observacoes", "obs", "nota"),
        total = asDouble(firstValue(raw, "total", "valorTotal", "totalPedido", "subtotal")),
        subtotal = asDouble(firstValue(raw, "subtotal", "totalProdutos")),
        freight = asDouble(firstValue(raw, "taxaEntrega", "valorEntrega", "frete")),
        discount = asDouble(firstValue(raw, "desconto", "valorDesconto")),
        createdMillis = asLongTime(firstValue(raw, "createdAt", "criadoEm", "timestamp", "dataCriacao", "data")),
        payment = payment,
        pickup = pickup,
        raw = raw,
    )
}

fun normalizeDriver(id: String, raw: Map<String, Any?>): Driver {
    val approvalStatuses = listOf("statusAprovacao", "statusCadastro", "situacao")
        .map { firstString(raw, it).uppercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
    val approved = raw["ativo"] != false && raw["aprovado"] != false &&
        approvalStatuses.none { it in setOf("PENDENTE", "REPROVADO", "BLOQUEADO", "AGUARDANDO APROVAÇÃO", "AGUARDANDO APROVACAO") }
    val operational = firstString(raw, "statusOperacional").uppercase(Locale.ROOT)
    val busy = raw["emCorrida"] == true || operational in
        setOf("EM_CORRIDA", "OCUPADO", "OFERTA_ACEITA", "PICKUP", "COLETANDO", "DELIVERY", "NO_CLIENTE", "EM_ENTREGA")
    val upRouteOpen = raw["upRouteOpen"] == true || raw["canReceiveRouteComplement"] == true
    val missionState = firstString(raw, "upMissionState", "upState").uppercase(Locale.ROOT)
    val beforePickup = missionState in setOf("TO_STORE", "AT_STORE") || operational in setOf("PICKUP", "COLETANDO")
    val canComplement = raw["online"] == true && approved && busy && upRouteOpen && beforePickup
    val battery = asDouble(firstValue(raw, "batteryLevel", "bateria", "bateriaPercentual")).toInt().takeIf { it in 0..100 }
    return Driver(
        id = id,
        name = firstString(raw, "nome", "nomeCompleto", default = "Entregador"),
        photoUrl = firstString(raw, "fotoUrl", "foto", "photoUrl", "selfieUrl", "fotoPerfil"),
        online = raw["online"] == true,
        approved = approved,
        busy = busy,
        acceptsOffers = raw["aceitaNovasOfertas"] != false,
        status = firstString(raw, "statusOperacional", "status", default = if (raw["online"] == true) "Disponível" else "Offline"),
        canReceiveComplement = canComplement,
        openRouteId = firstString(raw, "upOpenRouteId", "rotaAtualId", "rotaId"),
        batteryLevel = battery,
        raw = raw,
    )
}

fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)

fun timeText(millis: Long): String = if (millis <= 0) "—" else SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(millis))

data class StoreOperation(
    val open: Boolean = true,
    val pausedUntilMillis: Long = 0L,
    val maintenance: Boolean = false,
    val emergency: Boolean = false,
    val closedMessage: String = "",
    val demandMessage: String = "",
    val prepMinutes: Int = 25,
    val raw: Map<String, Any?> = emptyMap(),
) {
    val paused: Boolean get() = pausedUntilMillis > System.currentTimeMillis()
    val acceptingOrders: Boolean get() = open && !paused && !maintenance && !emergency
}

data class ChatSummary(
    val id: String,
    val orderId: String,
    val lastText: String,
    val lastSender: String,
    val lastTime: String,
    val timestamp: Long,
    val unreadForStore: Boolean,
)

data class OrderAlteration(
    val id: String,
    val orderId: String,
    val orderNumber: String,
    val clientName: String,
    val type: String,
    val currentItem: String,
    val newItem: String,
    val observation: String,
    val origin: String,
    val status: String,
    val createdMillis: Long,
) {
    val waitingStore: Boolean get() = origin.uppercase(Locale.ROOT) == "CLIENTE" && status.uppercase(Locale.ROOT) == "AGUARDANDO_LOJA"
    val waitingClient: Boolean get() = origin.uppercase(Locale.ROOT) == "GESTOR" && status.uppercase(Locale.ROOT) == "AGUARDANDO_CLIENTE"
}

data class PresenceSummary(
    val online: Int = 0,
    val home: Int = 0,
    val menu: Int = 0,
    val builder: Int = 0,
    val cart: Int = 0,
    val checkout: Int = 0,
    val tracking: Int = 0,
)

data class CatalogProduct(
    val id: String,
    val name: String,
    val category: String,
    val available: Boolean,
)
