package dev.channing.androidagent

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.net.URI

/** Remote plans contain no arbitrary text, coordinates, selectors or approval flags. */
data class TaskPlan(
    val taskId: String, val deviceId: String, val nonce: String,
    val issuedAt: Long, val expiresAt: Long, val actions: List<Action>
) {
    fun isCurrent(now: Long) = issuedAt <= now + 60 && expiresAt > now &&
        expiresAt > issuedAt && expiresAt - issuedAt <= 300
    fun summary() = actions.mapIndexed { i, a -> "${i + 1}. ${a.title}" }.joinToString("\n")
}

object PlanCodec {
    const val MAX_BYTES = 16_384
    private val id = Regex("[A-Za-z0-9_-]{16,80}")
    private val device = Regex("[A-Za-z0-9_-]{1,64}")
    private val keys = setOf("schemaVersion", "taskId", "deviceId", "nonce", "issuedAt", "expiresAt", "appPackage", "actions")
    fun parse(json: String, expectedDevice: String, now: Long): TaskPlan {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val fields = mutableMapOf<String, Any>()
        val r = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
        r.use {
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                require(name in keys && name !in fields)
                fields[name] = when (name) {
                    "schemaVersion", "issuedAt", "expiresAt" -> number(r)
                    "actions" -> steps(r)
                    else -> string(r)
                }
            }
            r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT)
        }
        require(fields.keys == keys && fields["schemaVersion"] == 1L)
        require(fields["appPackage"] == SafetyPolicy.PACKAGE)
        val taskId = fields["taskId"] as String
        val deviceId = fields["deviceId"] as String
        val nonce = fields["nonce"] as String
        require(id.matches(taskId) && id.matches(nonce) && device.matches(deviceId) && deviceId == expectedDevice)
        @Suppress("UNCHECKED_CAST")
        return TaskPlan(taskId, deviceId, nonce, fields["issuedAt"] as Long,
            fields["expiresAt"] as Long, fields["actions"] as List<Action>).also { require(it.isCurrent(now)) }
    }
    /** Manual copy/paste suggestions receive a NEW local envelope after the user imports them. */
    fun suggestions(raw: String, deviceId: String, now: Long): TaskPlan {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        require(device.matches(deviceId))
        val actions = mutableListOf<Action>()
        JsonReader(StringReader(raw)).apply { strictness = Strictness.STRICT }.use { r ->
            r.beginArray()
            while (r.hasNext()) { require(actions.size < 10); actions.add(Action.valueOf(string(r))) }
            r.endArray(); require(r.peek() == JsonToken.END_DOCUMENT && SafetyPolicy.permitsPlan(actions))
        }
        return TaskPlan(java.util.UUID.randomUUID().toString(), deviceId, java.util.UUID.randomUUID().toString(),
            now, now + 300, actions.toList())
    }
    private fun string(r: JsonReader): String {
        require(r.peek() == JsonToken.STRING)
        return r.nextString().also { require(it.length <= 256) }
    }
    private fun number(r: JsonReader): Long {
        require(r.peek() == JsonToken.NUMBER)
        val raw = r.nextString(); require(Regex("0|[1-9][0-9]{0,12}").matches(raw))
        return raw.toLong()
    }
    private fun steps(r: JsonReader): List<Action> {
        val result = mutableListOf<Action>(); r.beginArray()
        while (r.hasNext()) {
            require(result.size < 10)
            r.beginObject(); val seen = mutableSetOf<String>(); var type: String? = null; var target: String? = null
            while (r.hasNext()) {
                val name = r.nextName(); require(name in setOf("type", "target") && seen.add(name))
                if (name == "type") type = string(r)
                else if (r.peek() == JsonToken.NULL) r.nextNull() else target = string(r)
            }
            r.endObject(); require(seen == setOf("type", "target"))
            val action = Action.valueOf(requireNotNull(type))
            require(SafetyPolicy.permits(action, target)); result.add(action)
        }
        r.endArray(); require(SafetyPolicy.permitsPlan(result)); return result.toList()
    }
    fun demo(deviceId: String, now: Long): String = """
        {"schemaVersion":1,"taskId":"${java.util.UUID.randomUUID()}","deviceId":"$deviceId",
        "nonce":"${java.util.UUID.randomUUID()}","issuedAt":$now,"expiresAt":${now + 300},
        "appPackage":"${SafetyPolicy.PACKAGE}","actions":[
        {"type":"INSPECT","target":null},{"type":"CLICK","target":"demo_click"},
        {"type":"INPUT","target":"demo_input"},{"type":"SWIPE","target":"demo_scroll"}]}
    """.trimIndent()
}

/** Bound endpoint: no redirects, credentials in URLs, paths, queries or fragments. */
object EndpointPolicy {
    fun origin(raw: String): String {
        require(raw.length in 1..512 && raw == raw.trim())
        val u = URI(raw)
        require(u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null &&
            u.rawQuery == null && u.rawFragment == null && u.rawPath in listOf("", "/") &&
            (u.port == -1 || u.port in 1..65535))
        return raw.removeSuffix("/")
    }
}

object ReplayPolicy {
    fun reserve(records: Set<String>, plan: TaskPlan, now: Long): Set<String> {
        require(plan.isCurrent(now))
        val retained = records.filter { record ->
            val parts = record.split(':')
            require(parts.size == 3)
            parts[2].toLong() + 60 > now
        }.toMutableSet()
        require(retained.none { it.substringBefore(':') == plan.taskId || it.split(':')[1] == plan.nonce })
        require(retained.size < 1024)
        retained.add("${plan.taskId}:${plan.nonce}:${plan.expiresAt}")
        return retained.toSet()
    }
}

/** Pairing is user-pasted, never imported from the network task channel. */
object PairingCodec {
    data class Pairing(val origin: String, val deviceId: String, val deviceToken: String, val siteToken: String, val expiresAt: Long)
    fun parse(raw: String, expectedDevice: String, now: Long): Pairing {
        require(raw.toByteArray(Charsets.UTF_8).size <= PlanCodec.MAX_BYTES)
        val keys = setOf("origin", "deviceId", "deviceToken", "siteToken", "expiresAt")
        val values = mutableMapOf<String, String>()
        JsonReader(StringReader(raw)).apply { strictness = Strictness.STRICT }.use { r ->
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName(); require(name in keys && name !in values)
                if (name == "expiresAt") { require(r.peek() == JsonToken.NUMBER); val n = r.nextString(); require(Regex("[1-9][0-9]{0,12}").matches(n)); values[name] = n }
                else { require(r.peek() == JsonToken.STRING); values[name] = r.nextString().also { require(it.length <= 512) } }
            }
            r.endObject(); require(r.peek() == JsonToken.END_DOCUMENT)
        }
        require(values.keys == keys && values["deviceId"] == expectedDevice)
        val origin = EndpointPolicy.origin(requireNotNull(values["origin"]))
        val deviceToken = requireNotNull(values["deviceToken"]); val siteToken = requireNotNull(values["siteToken"])
        require(Regex("[A-Za-z0-9_-]{43,128}").matches(deviceToken) && Regex("[A-Za-z0-9_-]{43,128}").matches(siteToken))
        val expiry = requireNotNull(values["expiresAt"]).toLong(); require(expiry > now && expiry <= now + 3660)
        return Pairing(origin, expectedDevice, deviceToken, siteToken, expiry)
    }
}
