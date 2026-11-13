package com.rodrigues.gestor.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationEventPolicyTest {
    @Test fun paymentNotSuppressedByNewOrder() {
        val order = "order-ABC"
        val newOrder = NotificationEventPolicy.key(null,null,"NEW_ORDER",order,null)
        val payment = NotificationEventPolicy.key(null,null,"PAYMENT",order,"PAGO")
        val message = NotificationEventPolicy.key(null,null,"CLIENT_MESSAGE",order,null)
        assertNotEquals(newOrder,payment)
        assertNotEquals(newOrder,message)
        assertNotEquals(payment,message)
    }

    @Test fun sameEventStillDeduplicates() {
        assertEquals(
            NotificationEventPolicy.key(null,null,"NEW_ORDER","abc",null),
            NotificationEventPolicy.key(null,null,"NEW_ORDER","abc",null))
    }

    @Test fun messageIdOverridesGenericOrderFallback() {
        val a = NotificationEventPolicy.key(null,"message-1","PAYMENT","ABC",null)
        val b = NotificationEventPolicy.key(null,"message-2","PAYMENT","ABC",null)
        assertNotEquals(a,b)
    }

    @Test fun explicitEventIdOverridesMessageId() {
        assertEquals(
            NotificationEventPolicy.key("evt01","m1","NEW_ORDER","abc",null),
            NotificationEventPolicy.key("evt01","m2","PAYMENT","abc","PAGO"))
    }

    @Test fun statusChangesAreSeparateWhenMessageIdMissing() {
        val p=NotificationEventPolicy.key(null,null,"PAYMENT","abc","PENDENTE")
        val q=NotificationEventPolicy.key(null,null,"PAYMENT","abc","PAGO")
        assertNotEquals(p,q)
    }

    @Test fun missingIdentifiersDoesNotSpamDedupStore() {
        assertEquals("",NotificationEventPolicy.key(null,null,"PAYMENT","",null))
    }
}
