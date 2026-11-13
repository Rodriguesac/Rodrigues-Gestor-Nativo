package com.rodrigues.gestor.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealtimeReconnectGateTest {
    @Test fun oneReconnectForFailureAndCloseOnSameSocket() {
        val gate = RealtimeReconnectGate()
        val first = gate.newConnection()
        assertTrue(gate.requestReconnect(first))
        assertFalse(gate.requestReconnect(first))
        assertTrue(gate.consumeReconnect(first))
        val second = gate.newConnection()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.requestReconnect(first))
        assertTrue(gate.requestReconnect(second))
    }

    @Test fun callbackAntigoNaoLimpaAgendamentoDaNovaConexao() {
        val gate = RealtimeReconnectGate()
        val antigo = gate.newConnection()
        assertTrue(gate.requestReconnect(antigo))
        assertTrue(gate.consumeReconnect(antigo))
        val novo = gate.newConnection()
        assertTrue(gate.requestReconnect(novo))
        assertFalse(gate.consumeReconnect(antigo))
        assertFalse(gate.requestReconnect(novo))
        assertTrue(gate.consumeReconnect(novo))
    }

    @Test fun stopPreventsScheduledCallbackFromReopeningSocket() {
        val gate = RealtimeReconnectGate()
        val version = gate.newConnection()
        assertTrue(gate.requestReconnect(version))
        gate.stop()
        assertFalse(gate.consumeReconnect(version))
        assertFalse(gate.requestReconnect(version))
        assertFalse(gate.isCurrent(version))
    }

    @Test fun failureCallbacksRaceButOnlyOneSchedules() {
        val gate = RealtimeReconnectGate()
        val version = gate.newConnection()
        val threads = 12
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val scheduled = AtomicInteger(0)
        repeat(threads) {
            Thread {
                start.await()
                if (gate.requestReconnect(version)) scheduled.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertEquals(1, scheduled.get())
    }
}
