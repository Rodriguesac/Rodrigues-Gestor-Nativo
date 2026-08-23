package com.rodrigues.gestor.data

import com.google.firebase.Timestamp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
    val raw: Map<String, Any?>,
) {
    val available: Boolean get() = online && approved && !busy && acceptsOffers
}

data class TrackingPoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
) {
    val valid: Boolean get() = lat in -90.0..90.0 && lng in -180.0..180.0 && lat != 0.0 && lng != 0.0
}

data class DeliveryTracking(
    val driver: TrackingPoint = TrackingPoint(),
    val customer: TrackingPoint = TrackingPoint(),
    val store: TrackingPoint = TrackingPoint(),
    val updatedMillis: Long = 0L,
    val accuracyMeters: Double = 0.0,
    val speedMetersPerSecond: Double = 0.0,
    val traveledMeters: Double = 0.0,
    val remainingMeters: Double = 0.0,
    val routeMeters: Double = 0.0,
    val etaMinutes: Int = 0,
    val stopsBefore: Int = 0,
    val source: String = "",
) {
    val hasMap: Boolean get() = driver.valid && customer.valid

    fun remainingKm(): Double? {
        if (remainingMeters > 0.0) return remainingMeters / 1_000.0
        if (!hasMap) return null
        // Fallback conservador: a malha viária costuma ser maior que a linha reta.
        return haversineKm(driver, customer) * 1.22
    }

    fun etaRange(): IntRange? {
        val center = when {
            etaMinutes > 0 -> etaMinutes
            else -> {
                val km = remainingKm() ?: return null
                val measuredKmh = speedMetersPerSecond * 3.6
                val operationalKmh = measuredKmh.takeIf { it in 8.0..70.0 } ?: 24.0
                ((km / operationalKmh) * 60.0 + stopsBefore * 4.0 + 1.0).roundToInt().coerceAtLeast(2)
            }
        }
        val spread = max(2, ceil(center * 0.20).toInt())
        return max(2, center - spread)..max(center + 1, center + spread)
    }

    fun freshness(now: Long = System.currentTimeMillis()): String {
        if (updatedMillis <= 0L) return "Aguardando a primeira localização"
        val seconds = ((now - updatedMillis).coerceAtLeast(0L) / 1_000L).toInt()
        return when {
            seconds < 25 -> "Localização atualizada agora"
            seconds < 60 -> "Atualizada há $seconds segundos"
            else -> "Última localização há ${seconds / 60} min"
        }
    }

    fun stale(now: Long = System.currentTimeMillis()): Boolean =
        updatedMillis > 0L && now - updatedMillis >= 3 * 60_000L
}

fun haversineKm(a: TrackingPoint, b: TrackingPoint): Double {
    if (!a.valid || !b.valid) return 0.0
    val radius = 6_371.0
    val rad = Math.PI / 180.0
    val dLat = (b.lat - a.lat) * rad
    val dLng = (b.lng - a.lng) * rad
    val h = sin(dLat / 2).let { it * it } +
        cos(a.lat * rad) * cos(b.lat * rad) * sin(dLng / 2).let { it * it }
    return 2 * radius * asin(sqrt(min(1.0, h)))
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
    val NEW = setOf("AGUARDANDO_CONFIRMACAO", "RECEBIDO", "PENDENTE", "NOVO", "NOVO_PEDIDO")
    val CONFIRMED = setOf("CONFIRMADO", "FILA", "ACEITO")
    val PREPARING = setOf("EM_PREPARO", "PREPARANDO")
    val READY = setOf("PRONTO")
    val DELIVERY = setOf(
        "BUSCANDO_ENTREGADOR", "AGUARDANDO_ENTREGADOR", "AGUARDANDO_DECISAO_GESTOR",
        "A_CAMINHO_LOJA", "ENTREGADOR_A_CAMINHO_LOJA", "COLETANDO", "ENTREGADOR_CHEGOU_LOJA",
        "SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA", "ENTREGADOR_NO_LOCAL"
    )
    val DONE = setOf("ENTREGUE", "CONCLUIDO", "CONCLUÍDO", "FINALIZADO", "RETIRADO")
    val CANCELED = setOf("CANCELADO", "CANCELADA")

    fun label(status: String): String {
        val s = status.uppercase(Locale.ROOT)
        return when {
            s in NEW -> "Novo"
            s in CONFIRMED -> "Confirmado"
            s in PREPARING -> "Em preparo"
            s in READY -> "Pronto"
            s in DELIVERY -> "Em entrega"
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
    val busy = raw["emCorrida"] == true || firstString(raw, "statusOperacional").uppercase(Locale.ROOT) in
        setOf("EM_CORRIDA", "OCUPADO", "OFERTA_ACEITA", "EM_ENTREGA")
    return Driver(
        id = id,
        name = firstString(raw, "nome", "nomeCompleto", default = "Entregador"),
        photoUrl = firstString(raw, "fotoUrl", "foto", "photoUrl", "selfieUrl", "fotoPerfil"),
        online = raw["online"] == true,
        approved = approved,
        busy = busy,
        acceptsOffers = raw["aceitaNovasOfertas"] != false,
        status = firstString(raw, "statusOperacional", "status", default = if (raw["online"] == true) "Disponível" else "Offline"),
        raw = raw,
    )
}

private fun trackingPoint(value: Any?): TrackingPoint {
    if (value is com.google.firebase.firestore.GeoPoint) {
        return TrackingPoint(value.latitude, value.longitude)
    }
    @Suppress("UNCHECKED_CAST")
    val map = value as? Map<String, Any?> ?: return TrackingPoint()
    return TrackingPoint(
        lat = asDouble(firstValue(map, "lat", "latitude", "_lat")),
        lng = asDouble(firstValue(map, "lng", "lon", "longitude", "_long")),
    )
}

private fun firstTrackingPoint(vararg candidates: Any?): TrackingPoint =
    candidates.asSequence().map(::trackingPoint).firstOrNull { it.valid } ?: TrackingPoint()

private fun firstPositive(vararg candidates: Any?): Double =
    candidates.asSequence().map(::asDouble).firstOrNull { it > 0.0 } ?: 0.0

fun normalizeDeliveryTracking(
    orderRaw: Map<String, Any?>,
    missionRaw: Map<String, Any?> = emptyMap(),
    driverRaw: Map<String, Any?> = emptyMap(),
): DeliveryTracking {
    val delivery = mapValue(orderRaw, "entrega") ?: emptyMap()
    val address = mapValue(orderRaw, "endereco") ?: emptyMap()
    val deliveryAddress = mapValue(delivery, "endereco") ?: emptyMap()
    val orderDriverLocation = mapValue(orderRaw, "localizacaoEntregador") ?: emptyMap()
    val missionDriverLocation = mapValue(missionRaw, "localizacaoEntregador") ?: emptyMap()
    val driverCoords = mapValue(driverRaw, "coords")
        ?: mapValue(driverRaw, "localizacaoAtual")
        ?: mapValue(driverRaw, "localizacao")
        ?: emptyMap()
    val currentStop = mapValue(missionRaw, "paradaAtual") ?: emptyMap()

    val driverPoint = firstTrackingPoint(
        mapOf("lat" to orderRaw["entregadorLat"], "lng" to orderRaw["entregadorLng"]),
        orderDriverLocation,
        mapOf("lat" to missionRaw["entregadorLat"], "lng" to missionRaw["entregadorLng"]),
        missionDriverLocation,
        driverCoords,
    )
    val customerPoint = firstTrackingPoint(
        mapOf("lat" to orderRaw["clienteLat"], "lng" to orderRaw["clienteLng"]),
        orderRaw["clienteCoords"],
        orderRaw["destinoCoords"],
        address["coords"],
        deliveryAddress["coords"],
        address,
        deliveryAddress,
        currentStop["coords"],
        currentStop,
    )
    val storePoint = firstTrackingPoint(
        mapOf("lat" to orderRaw["lojaLat"], "lng" to orderRaw["lojaLng"]),
        orderRaw["lojaCoords"],
        missionRaw["lojaCoords"],
        mapOf("lat" to missionRaw["lojaLat"], "lng" to missionRaw["lojaLng"]),
    )

    val remainingMeters = firstPositive(
        orderRaw["distanciaRestanteMetros"], delivery["distanciaRestanteMetros"],
        missionRaw["distanciaRestanteMetros"], missionRaw["remainingDistanceMeters"],
    ).takeIf { it > 0.0 } ?: (firstPositive(
        orderRaw["distanciaRestanteKm"], delivery["distanciaRestanteKm"],
        missionRaw["distanciaRestanteKm"], missionRaw["remainingDistanceKm"],
    ) * 1_000.0)

    val routeMeters = firstPositive(
        orderRaw["distanciaRotaMetros"], delivery["distanciaRotaMetros"],
        missionRaw["distanciaRotaMetros"], missionRaw["routeDistanceMeters"],
    ).takeIf { it > 0.0 } ?: (firstPositive(
        orderRaw["distanciaRotaKm"], missionRaw["distanciaKm"], missionRaw["kmEstimado"],
    ) * 1_000.0)

    return DeliveryTracking(
        driver = driverPoint,
        customer = customerPoint,
        store = storePoint,
        updatedMillis = sequenceOf(
            orderRaw["localizacaoEntregadorAtualizadaEm"], orderDriverLocation["updatedAt"],
            missionRaw["localizacaoEntregadorAtualizadaEm"], missionDriverLocation["updatedAt"],
            driverRaw["localizacaoAtualizadaEm"], driverCoords["updatedAt"],
        ).map(::asLongTime).firstOrNull { it > 0L } ?: 0L,
        accuracyMeters = firstPositive(
            orderRaw["entregadorAccuracy"], orderDriverLocation["accuracy"],
            missionDriverLocation["accuracy"], driverCoords["accuracy"],
        ),
        speedMetersPerSecond = firstPositive(
            orderRaw["entregadorSpeed"], orderDriverLocation["speed"],
            missionRaw["entregadorSpeed"], missionDriverLocation["speed"], driverCoords["speed"],
        ),
        traveledMeters = firstPositive(
            orderRaw["distanciaPercorridaEntregaMetros"], delivery["distanciaPercorridaEntregaMetros"],
            orderDriverLocation["distanciaPercorridaEntregaMetros"],
            orderRaw["distanciaPercorridaMetros"], missionRaw["distanciaPercorridaEntregaMetros"],
            missionDriverLocation["distanciaPercorridaEntregaMetros"],
            missionRaw["distanciaPercorridaMetros"], driverRaw["distanciaPercorridaEntregaMetros"],
            driverCoords["distanciaPercorridaEntregaMetros"],
        ),
        remainingMeters = remainingMeters,
        routeMeters = routeMeters,
        etaMinutes = firstPositive(
            orderRaw["etaMinutos"], delivery["etaMinutos"], missionRaw["etaMinutos"],
            missionRaw["etaMinutes"],
        ).roundToInt(),
        stopsBefore = firstPositive(
            orderRaw["paradasAntesCliente"], orderRaw["paradasAntes"], orderRaw["entregasAntes"],
            currentStop["indice"], missionRaw["paradasAntesCliente"],
        ).roundToInt().coerceAtLeast(0),
        source = firstString(missionRaw, "metricasOrigem", "localizacaoOrigem").ifBlank {
            firstString(missionDriverLocation, "metricasOrigem").ifBlank {
                firstString(orderDriverLocation, "metricasOrigem")
            }
        }
            .ifBlank { firstString(driverRaw, "localizacaoOrigem") },
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
