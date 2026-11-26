package dev.channing.androidagent

import org.junit.Assert.*
import org.junit.Test

class SafetyPolicyTest {
    @Test fun rejectsExternalAndSpoofedPackages() {
        listOf(null, "com.tencent.mobileqq", "${SafetyPolicy.PACKAGE}.evil", "android")
            .forEach { assertFalse(SafetyPolicy.allowsPackage(it)) }
        assertTrue(SafetyPolicy.allowsPackage(SafetyPolicy.PACKAGE))
    }
    @Test fun neverAllowsSensitiveOrSendOrConfirmationTargets() {
        listOf("sensitive_password", "sensitive_otp", "demo_send", "button1", "unknown")
            .forEach { assertFalse(SafetyPolicy.allowsId(SafetyPolicy.PREFIX + it, false)) }
        assertFalse(SafetyPolicy.allowsId("android:id/button1", false))
        assertFalse(SafetyPolicy.allowsId(null, false))
    }
    @Test fun passwordFlagOverridesKnownId() {
        SafetyPolicy.readableIds.forEach {
            assertFalse(SafetyPolicy.allowsId(SafetyPolicy.PREFIX + it, true))
            assertTrue(SafetyPolicy.allowsId(SafetyPolicy.PREFIX + it, false))
        }
    }
    @Test fun cannotTurnClickIntoSendOrInputIntoDifferentTarget() {
        assertFalse(SafetyPolicy.permits(Action.CLICK, "demo_send"))
        assertFalse(SafetyPolicy.permits(Action.INPUT, "sensitive_otp"))
        assertFalse(SafetyPolicy.permits(Action.SWIPE, "demo_click"))
        assertFalse(SafetyPolicy.permits(Action.INSPECT, "demo_input"))
        Action.entries.forEach { assertTrue(SafetyPolicy.permits(it, it.target)) }
    }
    @Test fun consentIsSingleUse() {
        val lease = TaskLease(); lease.arm(Action.CLICK, 7, 100)
        assertTrue(lease.consume(Action.CLICK, 7, 101))
        assertFalse(lease.consume(Action.CLICK, 7, 102))
    }
    @Test fun wrongActionConsumesAuthorization() {
        val lease = TaskLease(); lease.arm(Action.CLICK, 7, 100)
        assertFalse(lease.consume(Action.INPUT, 7, 101))
        assertFalse(lease.consume(Action.CLICK, 7, 102))
    }
    @Test fun wrongWindowFailsClosed() {
        val lease = TaskLease(); lease.arm(Action.INPUT, 7, 100)
        assertFalse(lease.consume(Action.INPUT, 8, 101))
    }
    @Test fun expiredConsentFailsAtBoundary() {
        val lease = TaskLease(); lease.arm(Action.CLICK, 7, 100)
        assertFalse(lease.consume(Action.CLICK, 7, 15100))
    }
    @Test fun stopRevokesAuthorization() {
        val lease = TaskLease(); lease.arm(Action.SWIPE, 7, 100); lease.revoke()
        assertFalse(lease.consume(Action.SWIPE, 7, 101))
    }
    @Test fun freshProcessHasNoConsent() {
        assertFalse(TaskLease().consume(Action.CLICK, 7, 0))
    }
}
