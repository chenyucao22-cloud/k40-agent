package dev.channing.androidagent

import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** All calls are explicit user actions. No polling worker, background service or retry. */
class BridgeClient {
    companion object {
        private val clients = java.util.Collections.synchronizedSet(mutableSetOf<BridgeClient>())
        fun cancelAll() {
            val snapshot = synchronized(clients) { clients.toList() }
            snapshot.forEach { it.cancel() }
        }
    }
    private val epoch = BridgeEpoch.generation
    private val active = AtomicReference<HttpsURLConnection?>()
    @Volatile private var cancelled = false
    fun cancel() {
        cancelled = true
        active.getAndSet(null)?.let { connection ->
            // Revocation is immediate; socket teardown must not block the stop button's UI thread.
            Thread({ connection.disconnect() }, "bridge-cancel").apply { isDaemon = true }.start()
        }
    }
    fun syncConfig(config: BridgeCredentials.Config, deviceId: String): String =
        requireNotNull(exchange(config, deviceId, "config", null))

    fun request(config: BridgeCredentials.Config, deviceId: String, receipt: String? = null): String? =
        exchange(config, deviceId, if (receipt == null) "next" else "result", receipt)

    private fun exchange(config: BridgeCredentials.Config, deviceId: String, path: String, receipt: String?): String? {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(deviceId))
        val connection = URL(EndpointPolicy.origin(config.origin) + "/v1/devices/$deviceId/$path")
            .openConnection() as HttpsURLConnection
        clients.add(this)
        try {
            check(!cancelled && epoch == BridgeEpoch.generation)
            active.set(connection)
            check(!cancelled && epoch == BridgeEpoch.generation)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (config.siteToken.isNotEmpty()) {
                connection.setRequestProperty("OAI-Sites-Authorization", "Bearer ${config.siteToken}")
            }
            connection.setRequestProperty("Cache-Control", "no-store")
            if (receipt != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = receipt.toByteArray(Charsets.UTF_8); require(bytes.size <= 1024)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            check(!cancelled && epoch == BridgeEpoch.generation)
            if (status == 204) return null
            require(status == 200) // Redirects, auth errors and server failures fail closed.
            require(connection.contentType?.substringBefore(';')?.trim() == "application/json")
            val bytes = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (out.size() <= PlanCodec.MAX_BYTES) {
                    check(!cancelled && epoch == BridgeEpoch.generation)
                    val count = stream.read(buffer, 0, minOf(buffer.size, PlanCodec.MAX_BYTES + 1 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            require(bytes.size <= PlanCodec.MAX_BYTES); check(!cancelled && epoch == BridgeEpoch.generation)
            return bytes.toString(Charsets.UTF_8)
        } finally { active.compareAndSet(connection, null); connection.disconnect(); clients.remove(this) }
    }
}
