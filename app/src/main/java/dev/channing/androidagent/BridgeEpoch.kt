package dev.channing.androidagent

/** Emergency-stop generation also revokes plans still sitting on a preview screen. */
object BridgeEpoch {
    @Volatile var generation: Long = 0
        private set
    @Synchronized fun revoke() {
        generation++
        PlanHandoff.clear()
        BridgeClient.cancelAll()
    }
}
