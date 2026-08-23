package com.rodrigues.gestor.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class OrdersRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private var fallbackOrdersListener: ListenerRegistration? = null

    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration {
        var fallbackStarted = false
        val primary = db.collection("pedidos")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(120)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (!fallbackStarted) {
                        fallbackStarted = true
                        fallbackOrdersListener = db.collection("pedidos")
                            .limit(120)
                            .addSnapshotListener { fallback, fallbackError ->
                                if (fallbackError != null) onError(fallbackError)
                                else if (fallback != null) {
                                    val rows = fallback.documents.mapNotNull { doc ->
                                        @Suppress("UNCHECKED_CAST")
                                        val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
                                        normalizeOrder(doc.id, raw)
                                    }.sortedByDescending { it.createdMillis }
                                    onData(rows)
                                }
                            }
                    } else onError(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val rows = snapshot.documents.mapNotNull { doc ->
                        @Suppress("UNCHECKED_CAST")
                        val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
                        normalizeOrder(doc.id, raw)
                    }.sortedByDescending { it.createdMillis }
                    onData(rows)
                }
            }
        return object : ListenerRegistration {
            override fun remove() {
                primary.remove()
                fallbackOrdersListener?.remove()
                fallbackOrdersListener = null
            }
        }
    }

    fun listenDrivers(onData: (List<Driver>) -> Unit, onError: (Throwable) -> Unit): ListenerRegistration =
        db.collection("entregadores").addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.mapNotNull { doc ->
                @Suppress("UNCHECKED_CAST")
                val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
                normalizeDriver(doc.id, raw)
            }?.sortedWith(compareByDescending<Driver> { it.available }.thenBy { it.name }) ?: emptyList()
            onData(list)
        }

    fun updateStatus(order: Order, nextStatus: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val status = nextStatus.uppercase(Locale.ROOT)
        val patch = mutableMapOf<String, Any>(
            "status" to status,
            "statusPedido" to status,
            "statusLoja" to status,
            "statusAtualizadoEm" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "historicoStatus" to FieldValue.arrayUnion(
                mapOf(
                    "status" to status,
                    "titulo" to StatusGroups.label(status),
                    "data" to isoNow(),
                    "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE"
                )
            )
        )
        when (status) {
            "CONFIRMADO" -> {
                patch["aceitoEm"] = FieldValue.serverTimestamp()
                patch["acceptedAt"] = FieldValue.serverTimestamp()
            }
            "EM_PREPARO" -> {
                patch["preparoIniciadoEm"] = FieldValue.serverTimestamp()
                patch["montagemAssumidaPorNome"] = "Rodrigues Gestor"
                patch["montagemAssumidaEm"] = FieldValue.serverTimestamp()
            }
            "PRONTO" -> patch["prontoEm"] = FieldValue.serverTimestamp()
            "ENTREGUE", "FINALIZADO" -> {
                patch["entregueEm"] = FieldValue.serverTimestamp()
                patch["finishedAt"] = FieldValue.serverTimestamp()
                patch["finalizado"] = true
            }
        }
        db.collection("pedidos").document(order.id).update(patch)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun finishPickup(order: Order, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val patch = mapOf<String, Any>(
            "status" to "FINALIZADO",
            "statusPedido" to "FINALIZADO",
            "statusEntrega" to "RETIRADO",
            "entrega.status" to "RETIRADO",
            "tipoEntregaOperacional" to "RETIRADA",
            "entregaModo" to "RETIRADA",
            "finalizado" to true,
            "retiradoEm" to FieldValue.serverTimestamp(),
            "finishedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "statusAtualizadoEm" to FieldValue.serverTimestamp(),
            "historicoStatus" to FieldValue.arrayUnion(
                mapOf(
                    "status" to "FINALIZADO",
                    "titulo" to "Retirado no balcão",
                    "data" to isoNow(),
                    "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE"
                )
            )
        )
        db.collection("pedidos").document(order.id).update(patch)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun cancelOrder(order: Order, reason: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val cleanReason = reason.trim().ifBlank { "Cancelado pela loja" }
        val patch = mapOf<String, Any>(
            "status" to "CANCELADO",
            "statusPedido" to "CANCELADO",
            "statusLoja" to "CANCELADO",
            "cancelado" to true,
            "motivoCancelamento" to cleanReason,
            "canceladoEm" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "statusAtualizadoEm" to FieldValue.serverTimestamp(),
            "historicoStatus" to FieldValue.arrayUnion(
                mapOf(
                    "status" to "CANCELADO",
                    "titulo" to "Pedido cancelado",
                    "motivo" to cleanReason,
                    "data" to isoNow(),
                    "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE"
                )
            )
        )
        db.collection("pedidos").document(order.id).update(patch)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun ensureChat(order: Order, onReady: (String) -> Unit, onError: (Throwable) -> Unit) {
        findChatByPedidoId(order.id) { id ->
            if (id != null) onReady(id)
            else findChatByPedidoId(order.number) { idByNumber ->
                if (idByNumber != null) onReady(idByNumber)
                else {
                    val data = hashMapOf<String, Any>(
                        "assunto" to "Falar com Atendente",
                        "pedidoId" to order.id,
                        "mensagens" to emptyList<Map<String, Any>>(),
                        "digitandoCliente" to false,
                        "digitandoGestor" to false,
                        "gestorOnline" to true,
                        "lidaCliente" to false,
                        "lidaGestor" to true,
                        "ultimoAcesso" to FieldValue.serverTimestamp()
                    )
                    db.collection("chats").add(data)
                        .addOnSuccessListener { onReady(it.id) }
                        .addOnFailureListener(onError)
                }
            }
        }
    }

    private fun findChatByPedidoId(value: String, callback: (String?) -> Unit) {
        db.collection("chats").whereEqualTo("pedidoId", value).limit(1).get()
            .addOnSuccessListener { snapshot -> callback(snapshot.documents.firstOrNull()?.id) }
            .addOnFailureListener { callback(null) }
    }

    fun listenChat(chatId: String, onData: (OrderChat) -> Unit, onError: (Throwable) -> Unit): ListenerRegistration =
        db.collection("chats").document(chatId).addSnapshotListener { doc, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            if (doc == null || !doc.exists()) return@addSnapshotListener
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data as? Map<String, Any?> ?: emptyMap()
            val messages = (raw["mensagens"] as? List<*>)?.mapNotNull { any ->
                @Suppress("UNCHECKED_CAST")
                val m = any as? Map<String, Any?> ?: return@mapNotNull null
                ChatMessage(
                    sender = firstString(m, "remetente", default = "cliente"),
                    text = firstString(m, "texto"),
                    time = firstString(m, "horario"),
                    timestamp = asDouble(m["timestamp"]).toLong()
                )
            } ?: emptyList()
            onData(OrderChat(doc.id, firstString(raw, "pedidoId"), messages))
            doc.reference.update(
                mapOf(
                    "gestorOnline" to true,
                    "lidaGestor" to true,
                    "digitandoGestor" to false,
                    "ultimoAcesso" to FieldValue.serverTimestamp()
                )
            )
        }

    fun sendChatMessage(chatId: String, order: Order, text: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val clean = text.trim()
        if (clean.isBlank()) {
            onError(IllegalArgumentException("Digite a mensagem."))
            return
        }
        val message = mapOf<String, Any>(
            "remetente" to "gestor",
            "texto" to clean,
            "horario" to SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date()),
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("chats").document(chatId).update(
            mapOf(
                "mensagens" to FieldValue.arrayUnion(message),
                "gestorOnline" to true,
                "digitandoGestor" to false,
                "lidaGestor" to true,
                "lidaCliente" to false,
                "ultimoAcesso" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            db.collection("pedidos").document(order.id).update(
                mapOf(
                    "atendimento.chatAberto" to true,
                    "atendimento.ultimaMensagem" to clean,
                    "atendimento.ultimoAutor" to "LOJA",
                    "atendimento.precisaAtencao" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            onDone()
        }.addOnFailureListener(onError)
    }

    fun dispatchToDriver(
        order: Order,
        driver: Driver,
        repasse: Double,
        shareTracking: Boolean,
        offerSeconds: Int = 45,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (repasse <= 0.0) {
            onError(IllegalArgumentException("Informe o valor do repasse ao entregador."))
            return
        }
        if (!driver.available) {
            onError(IllegalStateException("Este entregador não está disponível."))
            return
        }
        loadStoreData { store ->
            val now = System.currentTimeMillis()
            val expirySeconds = offerSeconds.coerceIn(30, 120)
            val expires = Timestamp(Date(now + expirySeconds * 1000L))
            val rideId = "${order.id}_${driver.id}_$now"
            val routeId = "rota_${order.id}_${driver.id}_$now"
            val orderRef = db.collection("pedidos").document(order.id)
            val driverRef = db.collection("entregadores").document(driver.id)
            val rideRef = db.collection("rides").document(rideId)
            val routeRef = db.collection("rotas_entrega").document(routeId)
            val legacyRef = db.collection("corridas").document(rideId)

            db.runTransaction { tx ->
                val orderSnap = tx.get(orderRef)
                val driverSnap = tx.get(driverRef)
                if (!orderSnap.exists()) error("Pedido não encontrado.")
                if (!driverSnap.exists()) error("Entregador não encontrado.")
                @Suppress("UNCHECKED_CAST")
                val liveOrderRaw = orderSnap.data as? Map<String, Any?> ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val liveDriverRaw = driverSnap.data as? Map<String, Any?> ?: emptyMap()
                val liveOrder = normalizeOrder(order.id, liveOrderRaw)
                val liveDriver = normalizeDriver(driver.id, liveDriverRaw)
                if (liveOrder.status in StatusGroups.DONE || liveOrder.status in StatusGroups.CANCELED) error("Pedido já encerrado.")
                if (liveOrder.status !in (StatusGroups.READY + setOf("BUSCANDO_ENTREGADOR", "AGUARDANDO_ENTREGADOR", "AGUARDANDO_DECISAO_GESTOR"))) {
                    error("O pedido precisa estar pronto para chamar entregador.")
                }
                if (!liveDriver.available) error("Entregador ficou indisponível.")

                val deliveryCode = existingOrRandomCode(liveOrderRaw, "codigoEntrega", "codigoCurto")
                val pickupCode = existingOrRandomCode(liveOrderRaw, "codigoRetirada", "codigoLiberacao", "codigoParaRetirada")
                val common = hashMapOf<String, Any?>(
                    "schema" to "UP_V12_1_SEM_BLAZE",
                    "lojaId" to firstString(liveOrderRaw, "lojaId", default = "principal"),
                    "lojaNome" to firstString(liveOrderRaw, "lojaNome").ifBlank { store.name },
                    "pedidoId" to order.id,
                    "orderId" to order.id,
                    "numeroPedido" to liveOrder.number,
                    "codigoPedido" to liveOrder.number,
                    "routeId" to routeId,
                    "rotaId" to routeId,
                    "rotaEntregaId" to routeId,
                    "clienteNome" to liveOrder.clientName,
                    "clienteTelefone" to liveOrder.phone,
                    "bairro" to liveOrder.neighborhood,
                    "clienteBairro" to liveOrder.neighborhood,
                    "endereco" to liveOrder.address,
                    "dropoff" to liveOrder.address,
                    "clienteEnderecoCompleto" to liveOrder.address,
                    "deliveryAddress" to liveOrder.address,
                    "enderecoCliente" to liveOrder.address,
                    "destinoEndereco" to liveOrder.address,
                    "enderecoLoja" to store.address,
                    "coletaEndereco" to store.address,
                    "origemEndereco" to store.address,
                    "coletaNome" to store.name,
                    "lojaLat" to store.lat,
                    "lojaLng" to store.lng,
                    "formaPagamento" to liveOrder.payment.form,
                    "precisaMaquininha" to liveOrder.payment.needsMachine,
                    "precisaTroco" to liveOrder.payment.needsChange,
                    "trocoPara" to liveOrder.payment.changeFor,
                    "valorReceberCliente" to liveOrder.payment.amountToCollect,
                    "valorPedido" to liveOrder.total,
                    "valorRepasseEntregador" to repasse,
                    "valorCorrida" to repasse,
                    "entregadorGanho" to repasse,
                    "codigoEntrega" to deliveryCode,
                    "codigoRetirada" to pickupCode,
                    "codigoLiberacao" to pickupCode,
                    "deliveryCodeRequired" to true,
                    "rastreamentoClienteHabilitado" to shareTracking,
                    "rastreamentoVisivelCliente" to false,
                    "rastreamentoClienteAtualizadoEm" to FieldValue.serverTimestamp(),
                    "targetDriverId" to driver.id,
                    "ofertaParaEntregadorId" to driver.id,
                    "driverAtualOferta" to driver.id,
                    "entregadorAtualOferta" to driver.id,
                    "entregadorSelecionadoId" to driver.id,
                    "entregadorOfertaId" to driver.id,
                    "entregadorId" to driver.id,
                    "entregadorUid" to driver.id,
                    "driverId" to driver.id,
                    "driverName" to liveDriver.name,
                    "entregadorNome" to liveDriver.name,
                    "entregadorFoto" to liveDriver.photoUrl,
                    "ofertaParaTodos" to false,
                    "paraTodos" to false,
                    "broadcast" to false,
                    "ofertaAtiva" to true,
                    "ofertaAceita" to false,
                    "pendenteGestor" to false,
                    "status" to "OFERTA_ENVIADA",
                    "statusCorrida" to "OFERTA_ENVIADA",
                    "statusOferta" to "PENDENTE",
                    "statusOfertaEntregador" to "PENDENTE",
                    "statusEntrega" to "AGUARDANDO_ENTREGADOR",
                    "ofertaCriadaEm" to FieldValue.serverTimestamp(),
                    "ofertaEnviadaEmMs" to now,
                    "offerExpiresAt" to expires,
                    "ofertaExpiraEm" to expires,
                    "expiresAt" to expires,
                    "expiraEm" to expires,
                    "prazoRespostaOfertaMs" to expires.toDate().time,
                    "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE",
                    "origemDespacho" to "GESTOR_MANUAL",
                    "despachoManualConfirmado" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "criadoEm" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ).filterValues { it != null }

                tx.set(rideRef, common)
                tx.set(routeRef, HashMap<String, Any?>(common).apply {
                    put("id", routeId)
                    put("offerType", "NOVA_ROTA")
                    put("qtdPedidos", 1)
                    put("quantidadePedidos", 1)
                    put("pedidoIds", listOf(order.id))
                    put("pedidosIds", listOf(order.id))
                    put("rastreamentoPedidosHabilitados", if (shareTracking) listOf(order.id) else emptyList<String>())
                }, com.google.firebase.firestore.SetOptions.merge())
                tx.set(legacyRef, HashMap<String, Any?>(common).apply { put("sourceRideId", rideId) }, com.google.firebase.firestore.SetOptions.merge())
                tx.update(orderRef, mapOf(
                    "status" to "BUSCANDO_ENTREGADOR",
                    "statusPedido" to "BUSCANDO_ENTREGADOR",
                    "statusEntrega" to "AGUARDANDO_ENTREGADOR",
                    "entrega.status" to "AGUARDANDO_ENTREGADOR",
                    "entregaModo" to "UP",
                    "tipoEntregaOperacional" to "UP",
                    "corridaAtualId" to rideId,
                    "corridaNativaId" to rideId,
                    "rotaAtualId" to routeId,
                    "rotaId" to routeId,
                    "entregadorSelecionadoId" to driver.id,
                    "entregadorOfertaId" to driver.id,
                    "entregadorAtualOferta" to driver.id,
                    "entregadorId" to driver.id,
                    "entregadorUid" to driver.id,
                    "driverId" to driver.id,
                    "entrega.entregadorId" to driver.id,
                    "entrega.entregadorNome" to liveDriver.name,
                    "entregadorNome" to liveDriver.name,
                    "entregadorFoto" to liveDriver.photoUrl,
                    "ofertaAtiva" to true,
                    "ofertaAceita" to false,
                    "ofertaParaTodos" to false,
                    "broadcast" to false,
                    "pendenteGestor" to false,
                    "codigoEntrega" to deliveryCode,
                    "codigoRetirada" to pickupCode,
                    "codigoLiberacao" to pickupCode,
                    "deliveryCodeRequired" to true,
                    "rastreamentoClienteHabilitado" to shareTracking,
                    "rastreamentoVisivelCliente" to false,
                    "rastreamentoClienteAtualizadoEm" to FieldValue.serverTimestamp(),
                    "valorRepasseEntregador" to repasse,
                    "enderecoLoja" to store.address,
                    "coletaEndereco" to store.address,
                    "lojaLat" to (store.lat ?: 0.0),
                    "lojaLng" to (store.lng ?: 0.0),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "statusAtualizadoEm" to FieldValue.serverTimestamp(),
                    "historicoStatus" to FieldValue.arrayUnion(
                        mapOf(
                            "status" to "BUSCANDO_ENTREGADOR",
                            "titulo" to "Oferta UP para ${liveDriver.name}",
                            "data" to isoNow(),
                            "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE"
                        )
                    )
                ))
                tx.update(driverRef, mapOf(
                    "statusOperacional" to "OFERTA_ENVIADA",
                    "ofertaAtualId" to rideId,
                    "aceitaNovasOfertas" to false,
                    "ultimaOfertaEm" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                rideId
            }.addOnSuccessListener { createdRideId ->
                db.collection("alertas_operacionais").add(
                    mapOf(
                        "tipo" to "OFERTA_DIRECIONADA_ENTREGADOR_NATIVO",
                        "titulo" to "Oferta enviada para ${driver.name}",
                        "mensagem" to "Pedido #${order.number} enviado somente para ${driver.name}.",
                        "pedidoId" to order.id,
                        "codigoPedido" to order.number,
                        "rideId" to createdRideId,
                        "entregadorId" to driver.id,
                        "prioridade" to "ALTA",
                        "setor" to "LOGISTICA",
                        "resolvido" to false,
                        "fonte" to "rodrigues_gestor_android_native",
                        "criadoEm" to FieldValue.serverTimestamp(),
                        "atualizadoEm" to FieldValue.serverTimestamp()
                    )
                )
                db.collection("app_notifications").add(
                    mapOf(
                        "actionTarget" to createdRideId,
                        "actionType" to "ride",
                        "active" to true,
                        "categoria" to "Corrida",
                        "category" to "Entrega",
                        "channel" to "Firestore",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "criadoEm" to FieldValue.serverTimestamp(),
                        "origem" to "RODRIGUES_GESTOR_ANDROID_NATIVE",
                        "pedidoId" to order.id,
                        "rideId" to createdRideId,
                        "priority" to "ALTA",
                        "status" to "REGISTRADA",
                        "targetDriverId" to driver.id,
                        "targetDriverIds" to listOf(driver.id),
                        "targetGroup" to "entregadores",
                        "title" to "Nova corrida UP",
                        "titulo" to "Nova corrida UP",
                        "message" to "Pedido #${order.number} enviado para você.",
                        "mensagem" to "Pedido #${order.number} enviado para você."
                    )
                )
                onDone(createdRideId)
            }.addOnFailureListener(onError)
        }
    }

    fun setCustomerTracking(
        order: Order,
        enabled: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val delivery = mapValue(order.raw, "entrega") ?: emptyMap()
        val statuses = listOf(
            firstString(order.raw, "upState"),
            firstString(order.raw, "statusEntrega"),
            firstString(delivery, "status"),
            order.status,
        ).map { it.trim().uppercase(Locale.ROOT) }
        val afterPickup = statuses.any {
            it in setOf(
                "TO_CUSTOMER", "AT_CUSTOMER", "EM_ENTREGA", "NO_CLIENTE",
                "SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE",
                "ENTREGADOR_NO_LOCAL", "ENTREGADOR_CHEGOU_CLIENTE"
            )
        }
        val visibleNow = enabled && afterPickup
        val timestamp = FieldValue.serverTimestamp()
        val missionPatch = mapOf<String, Any>(
            "rastreamentoClienteHabilitado" to enabled,
            "rastreamentoVisivelCliente" to visibleNow,
            "rastreamentoClienteAtualizadoEm" to timestamp,
            "updatedAt" to timestamp,
        )
        val orderPatch = HashMap(missionPatch).apply {
            put("entrega.rastreamentoClienteHabilitado", enabled)
            put("entrega.rastreamentoVisivelCliente", visibleNow)
            put("statusAtualizadoEm", timestamp)
        }
        val batch = db.batch()
        batch.update(db.collection("pedidos").document(order.id), orderPatch)

        listOf("corridaAtualId", "corridaNativaId")
            .map { firstString(order.raw, it) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { id ->
                batch.set(db.collection("rides").document(id), missionPatch, com.google.firebase.firestore.SetOptions.merge())
            }
        listOf("rotaAtualId", "rotaId")
            .map { firstString(order.raw, it) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { id ->
                val routePatch = HashMap(missionPatch).apply {
                    put(
                        "rastreamentoPedidosHabilitados",
                        if (enabled) FieldValue.arrayUnion(order.id) else FieldValue.arrayRemove(order.id)
                    )
                }
                batch.set(db.collection("rotas_entrega").document(id), routePatch, com.google.firebase.firestore.SetOptions.merge())
            }

        batch.commit().addOnSuccessListener { onDone() }.addOnFailureListener(onError)
    }

    fun listenDeliveryTracking(
        order: Order,
        onData: (DeliveryTracking) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration {
        val routeId = firstString(order.raw, "rotaAtualId", "rotaId")
        val rideId = firstString(order.raw, "corridaAtualId", "corridaNativaId")
        val collection = if (routeId.isNotBlank()) "rotas_entrega" else "rides"
        val missionId = routeId.ifBlank { rideId }
        if (missionId.isBlank()) {
            onData(normalizeDeliveryTracking(order.raw))
            return object : ListenerRegistration { override fun remove() = Unit }
        }
        return db.collection(collection).document(missionId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            @Suppress("UNCHECKED_CAST")
            val mission = snapshot?.data as? Map<String, Any?> ?: emptyMap()
            onData(normalizeDeliveryTracking(order.raw, mission))
        }
    }



    fun listenCatalogProducts(
        onData: (List<CatalogProduct>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = db.collection("catalogo_produtos").limit(250).addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val rows = snapshot?.documents?.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
            CatalogProduct(
                id = doc.id,
                name = firstString(raw, "nome", "titulo", "n", default = doc.id),
                category = firstString(raw, "categoriaNome", "categoria", "departamento"),
                available = raw["disponivel"] != false && raw["pausado"] != true && raw["ativo"] != false,
            )
        }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()
        onData(rows)
    }

    fun setCatalogProductAvailable(product: CatalogProduct, available: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        db.collection("catalogo_produtos").document(product.id)
            .update(
                mapOf(
                    "disponivel" to available,
                    "pausado" to !available,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun listenOperation(
        onData: (StoreOperation) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = db.collection("gadm_operacao").document("master")
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            @Suppress("UNCHECKED_CAST")
            val raw = snapshot?.data as? Map<String, Any?> ?: emptyMap()
            val pauseMillis = asLongTime(raw["pausaAte"])
            onData(
                StoreOperation(
                    open = raw["aberta"] != false,
                    pausedUntilMillis = pauseMillis,
                    maintenance = raw["manutencao"] == true,
                    emergency = raw["emergencia"] == true,
                    closedMessage = firstString(raw, "mensagemFechada"),
                    demandMessage = firstString(raw, "avisoDemanda"),
                    prepMinutes = asDouble(firstValue(raw, "tempoPreparoMin", "tempoPreparo", "previsaoMinutos"))
                        .toInt().takeIf { it > 0 } ?: 25,
                    raw = raw,
                )
            )
        }

    fun setStoreOpen(open: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val patch = hashMapOf<String, Any>(
            "modo" to "manual",
            "aberta" to open,
            "emergencia" to false,
            "manutencao" to false,
            "updatedAt" to FieldValue.serverTimestamp(),
            "atualizadoEm" to FieldValue.serverTimestamp(),
        )
        if (open) patch["pausaAte"] = FieldValue.delete()
        db.collection("gadm_operacao").document("master")
            .set(patch, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun pauseStore(minutes: Int, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        val duration = minutes.coerceIn(5, 240)
        val until = Timestamp(Date(System.currentTimeMillis() + duration * 60_000L))
        db.collection("gadm_operacao").document("master")
            .set(
                mapOf(
                    "modo" to "manual",
                    "aberta" to true,
                    "pausaAte" to until,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "atualizadoEm" to FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun clearStorePause(onDone: () -> Unit, onError: (Throwable) -> Unit) {
        db.collection("gadm_operacao").document("master")
            .set(
                mapOf(
                    "aberta" to true,
                    "pausaAte" to FieldValue.delete(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "atualizadoEm" to FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun updateOperationSettings(
        prepMinutes: Int,
        closedMessage: String,
        demandMessage: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        db.collection("gadm_operacao").document("master")
            .set(
                mapOf(
                    "tempoPreparoMin" to prepMinutes.coerceIn(5, 180),
                    "mensagemFechada" to closedMessage.trim(),
                    "avisoDemanda" to demandMessage.trim(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "atualizadoEm" to FieldValue.serverTimestamp(),
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun listenChats(
        onData: (List<ChatSummary>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = db.collection("chats").limit(120).addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val rows = snapshot?.documents?.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
            val messages = raw["mensagens"] as? List<*> ?: emptyList<Any?>()
            @Suppress("UNCHECKED_CAST")
            val last = messages.lastOrNull() as? Map<String, Any?> ?: emptyMap()
            ChatSummary(
                id = doc.id,
                orderId = firstString(raw, "pedidoId"),
                lastText = firstString(last, "texto"),
                lastSender = firstString(last, "remetente"),
                lastTime = firstString(last, "horario"),
                timestamp = asDouble(last["timestamp"]).toLong(),
                unreadForStore = raw["lidaGestor"] == false,
            )
        }?.sortedWith(compareByDescending<ChatSummary> { it.unreadForStore }.thenByDescending { it.timestamp }) ?: emptyList()
        onData(rows)
    }

    fun listenAlterations(
        onData: (List<OrderAlteration>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = db.collection("alteracoes_pedido").limit(120).addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val rows = snapshot?.documents?.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data as? Map<String, Any?> ?: return@mapNotNull null
            OrderAlteration(
                id = doc.id,
                orderId = firstString(raw, "pedidoId"),
                orderNumber = firstString(raw, "pedidoNumero"),
                clientName = firstString(raw, "clienteNome", default = "Cliente"),
                type = firstString(raw, "tipo", default = "ALTERAÇÃO"),
                currentItem = firstString(raw, "itemAtual"),
                newItem = firstString(raw, "novoItem"),
                observation = firstString(raw, "observacao", "descricao"),
                origin = firstString(raw, "origem"),
                status = firstString(raw, "status"),
                createdMillis = asDouble(raw["criadoEmMs"]).toLong().takeIf { it > 0 }
                    ?: asLongTime(raw["criadoEm"]),
            )
        }?.sortedByDescending { it.createdMillis } ?: emptyList()
        onData(rows)
    }

    fun resolveAlteration(
        alteration: OrderAlteration,
        approved: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val status = if (approved) "APROVADO_LOJA" else "RECUSADO_LOJA"
        db.collection("alteracoes_pedido").document(alteration.id)
            .set(
                mapOf(
                    "status" to status,
                    "decisaoLoja" to if (approved) "APROVADO" else "RECUSADO",
                    "respondidoLojaEm" to FieldValue.serverTimestamp(),
                    "respondidoLojaEmMs" to System.currentTimeMillis(),
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun proposeAlteration(
        order: Order,
        type: String,
        currentItem: String,
        newItem: String,
        description: String,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        db.collection("alteracoes_pedido").add(
            mapOf(
                "pedidoId" to order.id,
                "pedidoNumero" to order.number,
                "clienteUid" to order.clientUid,
                "clienteNome" to order.clientName,
                "tipo" to type.uppercase(Locale.ROOT),
                "itemAtual" to currentItem.trim(),
                "novoItem" to newItem.trim(),
                "observacao" to description.trim(),
                "descricao" to description.trim().ifBlank {
                    listOf(currentItem.trim(), newItem.trim()).filter { it.isNotBlank() }.joinToString(" → ")
                },
                "origem" to "GESTOR",
                "status" to "AGUARDANDO_CLIENTE",
                "criadoEm" to FieldValue.serverTimestamp(),
                "criadoEmMs" to System.currentTimeMillis(),
            )
        ).addOnSuccessListener { onDone() }.addOnFailureListener(onError)
    }


    fun setOperationalIssue(
        order: Order,
        type: String,
        note: String,
        active: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val patch = if (active) {
            mapOf<String, Any>(
                "problemaOperacional.ativo" to true,
                "problemaOperacional.tipo" to type,
                "problemaOperacional.observacao" to note.trim(),
                "problemaOperacional.criadoEm" to FieldValue.serverTimestamp(),
                "atendimento.precisaAtencao" to true,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        } else {
            mapOf<String, Any>(
                "problemaOperacional.ativo" to false,
                "problemaOperacional.resolvidoEm" to FieldValue.serverTimestamp(),
                "atendimento.precisaAtencao" to false,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        }
        db.collection("pedidos").document(order.id).update(patch)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun setPaymentPaid(order: Order, paid: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        db.collection("pedidos").document(order.id)
            .update(
                mapOf(
                    "pagamento.status" to if (paid) "PAGO" else "PENDENTE",
                    "pagamentoStatus" to if (paid) "PAGO" else "PENDENTE",
                    "pagamentoConfirmado" to paid,
                    "pagamentoAtualizadoEm" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
            .addOnSuccessListener { onDone() }
            .addOnFailureListener(onError)
    }

    fun listenPresence(
        onData: (PresenceSummary) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = db.collection("presenca_site").limit(300).addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val cutoff = System.currentTimeMillis() - 90_000L
        var online = 0
        var home = 0
        var menu = 0
        var builder = 0
        var cart = 0
        var checkout = 0
        var tracking = 0
        snapshot?.documents?.forEach { doc ->
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data as? Map<String, Any?> ?: return@forEach
            val active = raw["active"] != false && raw["visible"] != false
            val updated = asDouble(raw["updatedAtMs"]).toLong().takeIf { it > 0 } ?: asLongTime(raw["updatedAt"])
            if (!active || updated < cutoff) return@forEach
            online++
            val page = firstString(raw, "page", "rota").lowercase(Locale.ROOT)
            when {
                "checkout" in page -> checkout++
                "carrinho" in page || "sacola" in page -> cart++
                "montar" in page || "monte" in page -> builder++
                "acompan" in page || "sucesso" in page -> tracking++
                "cardap" in page || "bebidas" in page || "busca" in page -> menu++
                else -> home++
            }
        }
        onData(PresenceSummary(online, home, menu, builder, cart, checkout, tracking))
    }

    private data class StoreData(
        val name: String = "Rodrigues Açaí e Cia",
        val address: String = "",
        val lat: Double? = null,
        val lng: Double? = null,
    )

    private fun loadStoreData(callback: (StoreData) -> Unit) {
        val collections = listOf("config", "configuracao_cardapio", "configuracoes", "configuracoesLoja", "configuracoes_loja", "config_loja")
        fun tryCollection(index: Int) {
            if (index >= collections.size) {
                callback(StoreData())
                return
            }
            db.collection(collections[index]).limit(20).get()
                .addOnSuccessListener { snapshot ->
                    for (doc in snapshot.documents) {
                        @Suppress("UNCHECKED_CAST")
                        val data = doc.data as? Map<String, Any?> ?: continue
                        val address = firstString(data, "enderecoColeta", "endereco", "enderecoCompleto")
                        val lat = asDouble(firstValue(data, "latitudeLoja", "lat")).takeIf { it != 0.0 }
                        val lng = asDouble(firstValue(data, "longitudeLoja", "lng", "lon")).takeIf { it != 0.0 }
                        if (address.isNotBlank() || lat != null || lng != null) {
                            callback(
                                StoreData(
                                    name = firstString(data, "nomeLoja", "nome", default = "Rodrigues Açaí e Cia"),
                                    address = address,
                                    lat = lat,
                                    lng = lng,
                                )
                            )
                            return@addOnSuccessListener
                        }
                    }
                    tryCollection(index + 1)
                }
                .addOnFailureListener { tryCollection(index + 1) }
        }
        tryCollection(0)
    }

    private fun existingOrRandomCode(raw: Map<String, Any?>, vararg keys: String): String {
        val existing = firstString(raw, *keys).filter { it.isDigit() }
        if (existing.length >= 4) return existing.takeLast(4)
        return (1000..9999).random().toString()
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()).format(Date())
}
