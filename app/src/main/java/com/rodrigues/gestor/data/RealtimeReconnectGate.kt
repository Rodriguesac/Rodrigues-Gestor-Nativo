package com.rodrigues.gestor.data

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Controle isolado e testável dos ciclos Realtime.
 * Evita múltiplas reconexões por callbacks onFailure/onClosed concorrentes;
 * eventos de sockets antigos não podem reiniciar uma sessão substituída.
 */
class RealtimeReconnectGate {
    private val generation = AtomicInteger(0)
    private val pending = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    fun newConnection(): Int {
        check(!stopped.get()) { "Listener já encerrado." }
        return generation.incrementAndGet()
    }

    fun isCurrent(version: Int): Boolean =
        !stopped.get() && generation.get() == version

    fun requestReconnect(version: Int): Boolean =
        isCurrent(version) && pending.compareAndSet(false, true)

    fun consumeReconnect(version: Int): Boolean {
        // O callback de uma conexão antiga não pode zerar o agendamento
        // já reservado pelo socket novo.
        if (!isCurrent(version)) return false
        pending.set(false)
        return isCurrent(version)
    }

    fun stop() {
        stopped.set(true)
        generation.incrementAndGet()
        pending.set(false)
    }
}
