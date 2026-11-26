package dev.channing.androidagent

import org.junit.Assert.*
import org.junit.Test

class TaskPlanTest {
    private val device = "test-device"
    private val now = 2_000_000_000L
    private fun sample() = PlanCodec.demo(device, now)
    private fun parse(raw: String) = PlanCodec.parse(raw, device, now)
    private fun rejects(raw: String) {
        assertThrows(Exception::class.java) { parse(raw) }
    }
    @Test fun appLaunchRequiresAStandalonePlanAndFixedTarget() {
        val now = 2000L
        val suggestions = PlanCodec.suggestions("""["OPEN_QQ"]""", "test-device", now)
        assertEquals(listOf(Action.OPEN_QQ), suggestions.actions)
        val original = PlanCodec.demo("test-device", now)
        val prefix = original.substringBefore("\"actions\":")
        val qq = """{"type":"OPEN_QQ","target":"com.tencent.mobileqq"}"""
        val timer = """{"type":"TIMER_5_MIN","target":"com.android.deskclock"}"""
        assertEquals(listOf(Action.OPEN_QQ), PlanCodec.parse(
            prefix + """"actions":[$qq]}""", "test-device", now).actions)
        assertEquals(listOf(Action.TIMER_5_MIN), PlanCodec.parse(
            prefix + """"actions":[$timer]}""", "test-device", now).actions)
        for (stepList in listOf("$qq,$timer", qq.replace("com.tencent.mobileqq", "com.example.other"))) {
            assertThrows(IllegalArgumentException::class.java) {
                PlanCodec.parse(prefix + """"actions":[$stepList]}""", "test-device", now)
            }
        }
    }

    @Test fun validPublicDemo() { assertEquals(Action.entries.filterNot(Action::opensApp), parse(sample()).actions) }
    @Test fun rejectsOtherDevice() { rejects(sample().replace(device, "other-device")) }
    @Test fun rejectsOtherApp() { rejects(sample().replace(SafetyPolicy.PACKAGE, "com.tencent.mobileqq")) }
    @Test fun rejectsExpiredPlan() {
        assertThrows(Exception::class.java) { PlanCodec.parse(sample(), device, now + 300) }
    }
    @Test fun rejectsFutureIssue() {
        assertThrows(Exception::class.java) { PlanCodec.parse(sample(), device, now - 61) }
    }
    @Test fun rejectsLongValidity() { rejects(sample().replace("${now + 300}", "${now + 301}")) }
    @Test fun rejectsUnknownApprovalField() { rejects(sample().replace("{\"schemaVersion\"", "{\"approved\":true,\"schemaVersion\"")) }
    @Test fun rejectsDuplicateTopLevelKeys() { rejects(sample().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")) }
    @Test fun rejectsDuplicateActionKeys() { rejects(sample().replace("\"type\":\"CLICK\"", "\"type\":\"CLICK\",\"type\":\"CLICK\"")) }
    @Test fun rejectsTrailingAndComments() {
        rejects(sample() + "{}")
        rejects("/*comment*/" + sample())
    }
    @Test fun rejectsStringOrFractionalVersion() {
        rejects(sample().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))
        rejects(sample().replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"))
    }
    @Test fun rejectsSendAndShell() {
        rejects(sample().replace("\"CLICK\"", "\"SEND\""))
        rejects(sample().replace("\"CLICK\"", "\"SHELL\""))
    }
    @Test fun rejectsArbitraryTextAndTargets() {
        rejects(sample().replace("\"target\":\"demo_input\"", "\"target\":\"demo_input\",\"text\":\"private\""))
        rejects(sample().replace("demo_click", "demo_send"))
        rejects(sample().replace("demo_input", "sensitive_password"))
    }
    @Test fun rejectsOversizeDocument() { rejects(" ".repeat(PlanCodec.MAX_BYTES + 1)) }
    @Test fun rejectsEmptyAndTooManyActions() {
        val raw = sample(); val prefix = raw.substringBefore("\"actions\":")
        rejects(prefix + "\"actions\":[]}")
        rejects(prefix + "\"actions\":[" + List(11) { "{\"type\":\"INSPECT\",\"target\":null}" }.joinToString(",") + "]}")
    }
    @Test fun rejectsMissingTargetAndNullType() {
        rejects(sample().replace(",\"target\":null", ""))
        rejects(sample().replace("\"type\":\"INSPECT\"", "\"type\":null"))
    }
    @Test fun repeatedTaskOrNonceIsRejected() {
        val plan = parse(sample()); val records = ReplayPolicy.reserve(emptySet(), plan, now)
        assertThrows(Exception::class.java) { ReplayPolicy.reserve(records, plan, now) }
        assertThrows(Exception::class.java) { ReplayPolicy.reserve(records, plan.copy(taskId = "another-valid-task-id"), now) }
        assertThrows(Exception::class.java) { ReplayPolicy.reserve(records, plan.copy(nonce = "another-valid-nonce"), now) }
    }
    @Test fun retainingReplayRecordsDoesNotEvictUnexpiredEntries() {
        val plan = parse(sample())
        val records = (1..1024).map { "id$it:nonce$it:${now + 300}" }.toSet()
        assertThrows(Exception::class.java) { ReplayPolicy.reserve(records, plan, now) }
        assertEquals(1, ReplayPolicy.reserve(setOf("expired:expired:${now - 61}"), plan, now).size)
    }
    @Test fun manualSuggestionListGetsLocalEnvelope() {
        val a = PlanCodec.suggestions("[\"INSPECT\",\"CLICK\"]", device, now)
        val b = PlanCodec.suggestions("[\"INSPECT\",\"CLICK\"]", device, now)
        assertEquals(listOf(Action.INSPECT, Action.CLICK), a.actions)
        assertNotEquals(a.taskId, b.taskId)
        assertEquals(device, a.deviceId)
    }
    @Test fun rejectsUnsafeManualSuggestions() {
        listOf("[]", "[\"SEND\"]", "[\"SHELL\"]", "[true]", "[\"CLICK\"]{}")
            .forEach { raw -> assertThrows(Exception::class.java) { PlanCodec.suggestions(raw, device, now) } }
    }
    @Test fun networkParserDoesNotAcceptManualSuggestionArray() { rejects("[\"CLICK\"]") }
    @Test fun acceptsOnlyHttpsOrigin() {
        assertEquals("https://example.com", EndpointPolicy.origin("https://example.com/"))
        assertEquals("https://example.com:8443", EndpointPolicy.origin("https://example.com:8443"))
    }
    @Test fun rejectsUnsafeEndpoints() {
        listOf("http://example.com", "https://u:p@example.com", "https://example.com/x", "https://example.com?x=1",
            "https://example.com#x", "https://example.com:0", " https://example.com", "https://example.com\\@evil.com")
            .forEach { raw -> assertThrows(Exception::class.java) { EndpointPolicy.origin(raw) } }
    }

    private fun pairing() = """{"origin":"https://bridge.example.com","deviceId":"$device","deviceToken":"${"a".repeat(43)}","siteToken":"${"b".repeat(43)}","expiresAt":${now+3600}}"""
    @Test fun pairingAcceptsOnlyThisDeviceAndHttps() {
        val parsed=PairingCodec.parse(pairing(),device,now)
        assertEquals("https://bridge.example.com",parsed.origin)
        assertThrows(Exception::class.java){PairingCodec.parse(pairing(),"other",now)}
        assertThrows(Exception::class.java){PairingCodec.parse(pairing().replace("https://","http://"),device,now)}
    }
    @Test fun pairingRejectsDuplicateUnknownAndExpiredFields() {
        assertThrows(Exception::class.java){PairingCodec.parse(pairing().replace("{", "{\"approved\":true,"),device,now)}
        assertThrows(Exception::class.java){PairingCodec.parse(pairing().replace("\"deviceId\":", "\"deviceId\":\"other\",\"deviceId\":"),device,now)}
        assertThrows(Exception::class.java){PairingCodec.parse(pairing(),device,now+3600)}
    }
    @Test fun pairingRejectsMissingSecretAndExcessiveLifetime() {
        assertThrows(Exception::class.java){PairingCodec.parse(pairing().replace("b".repeat(43),""),device,now)}
        assertThrows(Exception::class.java){PairingCodec.parse(pairing().replace("${now+3600}","${now+86400}"),device,now)}
    }
}
