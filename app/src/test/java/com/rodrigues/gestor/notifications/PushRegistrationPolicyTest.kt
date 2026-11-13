package com.rodrigues.gestor.notifications

import org.junit.Assert.*
import org.junit.Test

class PushRegistrationPolicyTest {
    @Test fun noRegistrationFromPreviewBuild() {
        assertFalse(PushRegistrationPolicy.allowed(true, "123456", "fcm-valid"))
    }

    @Test fun noRegistrationBeforeGestorLogin() {
        assertFalse(PushRegistrationPolicy.allowed(false, "", "fcm-valid"))
        assertFalse(PushRegistrationPolicy.allowed(false, "12345", "fcm-valid"))
        assertFalse(PushRegistrationPolicy.allowed(false, "abcdef", "fcm-valid"))
    }

    @Test fun noRegistrationWithEmptyFcmToken() {
        assertFalse(PushRegistrationPolicy.allowed(false, "123456", ""))
    }

    @Test fun authenticatedOwnerCanStartRegistration() {
        assertTrue(PushRegistrationPolicy.allowed(false, "123456", "fcm-valid"))
    }
}
