package com.rodrigues.gestor.voice

import android.content.Context
import com.rodrigues.gestor.data.SupabaseOrdersApi
import java.util.Locale

class VoiceCommandEngine(
    private val context: Context,
    private val speak: (String) -> Unit,
) {
    private data class Confirmation(
        val expiresAt: Long,
        val prompt: String,
        val action: () -> Unit,
    )

    private var confirmation: Confirmation? = null
    private var waitingCancelReason: Pair<VoiceOrder, Long>? = null

    fun expectsFollowUp(): Boolean {
        val now = System.currentTimeMillis()
        val confirm = confirmation
        if (confirm != null && confirm.expiresAt > now) return true
        if (confirm != null) confirmation = null
        val waiting = waitingCancelReason
        if (waiting != null && waiting.second > now) return true
        if (waiting != null) waitingCancelReason = null
        return false
    }

    fun handle(rawTranscript: String) {
        val phrase = VoiceStateStore.normalize(rawTranscript)
        if (phrase.isBlank()) return

        confirmation?.let { pending ->
            if (pending.expiresAt < System.currentTimeMillis()) {
                confirmation = null
            } else if (isYes(phrase)) {
                confirmation = null
                pending.action()
                return
            } else if (isNo(phrase)) {
                confirmation = null
                speak("Cancelado. Nenhuma alteração foi feita.")
                return
            } else {
                speak(pending.prompt + " Responda sim ou não.")
                return
            }
        }

        waitingCancelReason?.let { pending ->
            val order = pending.first
            val expiresAt = pending.second
            if (expiresAt >= System.currentTimeMillis()) {
                waitingCancelReason = null
                val reason = rawTranscript.trim().removePrefix("porque").trim()
                if (reason.length < 3) {
                    speak("Não entendi o motivo. Diga o motivo novamente.")
                    waitingCancelReason = order to (System.currentTimeMillis() + 12_000L)
                    return
                }
                askConfirmation(
                    "Cancelar o pedido " + order.number + " por " + reason + "?",
                    "Confirma o cancelamento do pedido " + order.number + "?",
                ) { cancel(order, reason) }
                return
            }
            waitingCancelReason = null
        }

        when {
            Regex(".*\\b(quantos|quantas)\\b.*\\bpedidos?\\b.*").matches(phrase) -> {
                val count = VoiceStateStore.activeOrders().size
                speak(if (count == 1) "Há 1 pedido em andamento." else "Há " + count + " pedidos em andamento.")
            }

            hasAny(phrase, "proximo pedido", "proxima comanda", "qual o proximo", "qual e o proximo") -> {
                val order = VoiceStateStore.nextOrder()
                if (order == null) speak("Não há pedido em andamento.")
                else {
                    VoiceStateStore.setActiveOrder(order.id)
                    speak("Próximo pedido " + order.number + ", " + order.clientName + ". " + shortItems(order))
                }
            }

            hasAny(phrase, "repetir comanda", "repita a comanda", "ler comanda", "leia a comanda", "o que falta", "que falta", "quais ingredientes") -> {
                val order = targetOrExplain(phrase) ?: return
                speak(VoiceStateStore.describe(order))
            }

            isCancelCommand(phrase) -> {
                val order = targetOrExplain(phrase) ?: return
                val reason = extractReason(rawTranscript)
                if (reason.isNullOrBlank()) {
                    waitingCancelReason = order to (System.currentTimeMillis() + 12_000L)
                    speak("Qual o motivo para cancelar o pedido " + order.number + "?")
                } else {
                    askConfirmation(
                        "Cancelar o pedido " + order.number + " por " + reason + "?",
                        "Confirma o cancelamento do pedido " + order.number + "?",
                    ) { cancel(order, reason) }
                }
            }

            hasAny(phrase, "fechar loja", "feche a loja", "encerrar loja", "parar pedidos") -> {
                askConfirmation(
                    "Fechar a loja para novos pedidos?",
                    "Confirma fechar a loja?",
                ) {
                    VoiceRemoteApi.setStoreOpen(context, false, speak, ::speakError)
                }
            }

            hasAny(phrase, "abrir loja", "abra a loja", "reativar loja", "retomar pedidos") -> {
                VoiceRemoteApi.setStoreOpen(context, true, speak, ::speakError)
            }

            isPauseItemCommand(phrase) -> {
                val target = extractItemTarget(phrase, false)
                if (target.isBlank()) speak("Diga qual item deve ser pausado.")
                else VoiceRemoteApi.setItemAvailable(context, target, false, speak, ::speakError)
            }

            isActivateItemCommand(phrase) -> {
                val target = extractItemTarget(phrase, true)
                if (target.isBlank()) speak("Diga qual item deve ser ativado.")
                else VoiceRemoteApi.setItemAvailable(context, target, true, speak, ::speakError)
            }

            hasAny(phrase, "aceitar", "aceite", "confirmar pedido", "confirme pedido") -> {
                targetOrExplain(phrase)?.let { order ->
                    setStatus(order, "CONFIRMADO", "Pedido " + order.number + " aceito.")
                }
            }

            hasAny(phrase, "iniciar preparo", "inicie preparo", "comecar preparo", "preparar pedido") -> {
                targetOrExplain(phrase)?.let { order ->
                    setStatus(order, "EM_PREPARO", "Preparo do pedido " + order.number + " iniciado.")
                }
            }

            hasAny(phrase, "pedido pronto", "marcar como pronto", "marque como pronto", "esta pronto", "ficou pronto") -> {
                targetOrExplain(phrase)?.let { order ->
                    setStatus(order, "PRONTO", "Pedido " + order.number + " marcado como pronto.")
                }
            }

            hasAny(phrase, "despachar", "despache", "saiu para entrega", "mandar para entrega") -> {
                val order = targetOrExplain(phrase) ?: return
                if (order.pickup) {
                    speak("O pedido " + order.number + " é retirada. Diga finalizar retirada.")
                } else {
                    setStatus(order, "EM_ENTREGA", "Pedido " + order.number + " despachado.")
                }
            }

            hasAny(phrase, "finalizar retirada", "retirado no balcao", "cliente retirou") -> {
                val order = targetOrExplain(phrase) ?: return
                SupabaseOrdersApi.finishPickup(
                    order.id,
                    { speak("Retirada do pedido " + order.number + " finalizada.") },
                    ::speakError,
                )
            }

            hasAny(phrase, "finalizar pedido", "finalizar entrega", "pedido entregue", "marcar entregue", "concluir pedido") -> {
                val order = targetOrExplain(phrase) ?: return
                if (order.pickup && order.status == "PRONTO") {
                    SupabaseOrdersApi.finishPickup(
                        order.id,
                        { speak("Retirada do pedido " + order.number + " finalizada.") },
                        ::speakError,
                    )
                } else {
                    setStatus(order, "CONCLUIDO", "Pedido " + order.number + " finalizado.")
                }
            }

            hasAny(phrase, "avancar", "avance", "proxima etapa", "mudar status", "seguir pedido") -> {
                val order = targetOrExplain(phrase) ?: return
                advance(order)
            }

            else -> speak("Não entendi o comando. Você pode dizer avançar esse pedido, despachar, pedido pronto, pausar um item ou abrir a loja.")
        }
    }

    private fun advance(order: VoiceOrder) {
        val status = order.status.uppercase(Locale.ROOT)
        when (status) {
            "PENDENTE", "NOVO", "RECEBIDO", "ENVIADO", "AGUARDANDO_CONFIRMACAO", "NOVO_PEDIDO" ->
                setStatus(order, "CONFIRMADO", "Pedido " + order.number + " aceito.")
            "CONFIRMADO", "ACEITO", "FILA" ->
                setStatus(order, "EM_PREPARO", "Preparo do pedido " + order.number + " iniciado.")
            "EM_PREPARO", "PREPARANDO" ->
                setStatus(order, "PRONTO", "Pedido " + order.number + " marcado como pronto.")
            "PRONTO" -> {
                if (order.pickup) {
                    SupabaseOrdersApi.finishPickup(
                        order.id,
                        { speak("Retirada do pedido " + order.number + " finalizada.") },
                        ::speakError,
                    )
                } else {
                    setStatus(order, "EM_ENTREGA", "Pedido " + order.number + " despachado.")
                }
            }
            "EM_ENTREGA", "SAIU_PARA_ENTREGA", "SAIU_ENTREGA", "A_CAMINHO_CLIENTE", "DESPACHADO" ->
                setStatus(order, "CONCLUIDO", "Pedido " + order.number + " finalizado.")
            else -> speak("O pedido " + order.number + " está em " + status.lowercase().replace('_', ' ') + " e não posso avançar automaticamente.")
        }
    }

    private fun setStatus(order: VoiceOrder, status: String, success: String) {
        SupabaseOrdersApi.updateStatus(order.id, status, { speak(success) }, ::speakError)
    }

    private fun cancel(order: VoiceOrder, reason: String) {
        SupabaseOrdersApi.cancelOrder(
            order.id,
            reason,
            { speak("Pedido " + order.number + " cancelado.") },
            ::speakError,
        )
    }

    private fun targetOrExplain(phrase: String): VoiceOrder? {
        val order = VoiceStateStore.defaultTarget(phrase)
        if (order == null) speak("Não encontrei um pedido em andamento. Abra uma comanda ou diga o número do pedido.")
        return order
    }

    private fun shortItems(order: VoiceOrder): String {
        val names = order.items.take(3).joinToString(", ") { item ->
            if (item.quantity > 1) item.quantity.toString() + " " + item.name else item.name
        }
        return if (names.isBlank()) "" else "Itens: " + names + "."
    }

    private fun extractReason(raw: String): String? {
        val lower = raw.lowercase(Locale("pt", "BR"))
        val index = lower.indexOf("porque")
        if (index < 0) return null
        return raw.substring(index + "porque".length).trim().takeIf { it.length >= 3 }
    }

    private fun askConfirmation(prompt: String, repeatPrompt: String, action: () -> Unit) {
        confirmation = Confirmation(
            expiresAt = System.currentTimeMillis() + 12_000L,
            prompt = repeatPrompt,
            action = action,
        )
        speak(prompt + " Responda sim ou não.")
    }

    private fun speakError(error: Throwable) {
        speak(error.message?.takeIf { it.isNotBlank() } ?: "Não foi possível executar o comando.")
    }

    private fun isYes(value: String) = value in setOf("sim", "confirmar", "confirmo", "pode", "pode sim", "isso")
    private fun isNo(value: String) = value in setOf("nao", "cancelar", "cancela", "deixa", "negativo")

    private fun hasAny(value: String, vararg terms: String): Boolean = terms.any { value.contains(it) }

    private fun isCancelCommand(value: String): Boolean =
        Regex("\\b(cancelar|cancele|rejeitar|rejeite)\\b").containsMatchIn(value) && value.contains("pedido")

    private fun isPauseItemCommand(value: String): Boolean =
        Regex("^(pausar|pause|desativar|desative|bloquear|bloqueie|acabou)\\b").containsMatchIn(value) &&
            !value.contains("loja")

    private fun isActivateItemCommand(value: String): Boolean =
        Regex("^(ativar|ative|reativar|reative|liberar|libere|despausar|despause)\\b").containsMatchIn(value) &&
            !value.contains("loja")

    private fun extractItemTarget(value: String, activating: Boolean): String {
        val verbs = if (activating)
            "^(ativar|ative|reativar|reative|liberar|libere|despausar|despause)\\s+"
        else
            "^(pausar|pause|desativar|desative|bloquear|bloqueie|acabou)\\s+"
        return value.replace(Regex(verbs), "")
            .replace(Regex("^(o|a|os|as|produto|item|ingrediente|adicional|acompanhamento)\\s+"), "")
            .trim()
    }
}
