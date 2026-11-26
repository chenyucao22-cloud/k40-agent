package dev.channing.androidagent

import android.content.Context
import java.util.UUID

object PlanStore {
    private const val STORE = "task_bridge"
    @Synchronized fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(p.edit().putString("device_id", it).commit())
        }
    }
    /** Reserve at import, before any confirmation. Cancelled plans remain consumed until expiry. */
    @Synchronized fun accept(context: Context, plan: TaskPlan, now: Long) {
        require(plan.isCurrent(now) && plan.deviceId == deviceId(context))
        val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val seen = ReplayPolicy.reserve(p.getStringSet("seen", emptySet()).orEmpty(), plan, now)
        check(p.edit().putStringSet("seen", seen).commit())
        AuditLog.add(context, AuditLog.Code.PLAN_ACCEPTED)
    }
    @Synchronized fun result(context: Context, plan: TaskPlan, completed: Int, code: String) {
        require(code in setOf("COMPLETE", "CANCELLED", "FAILED", "EXPIRED"))
        val json = """{"taskId":"${plan.taskId}","status":"$code","completedSteps":$completed}"""
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString("result", json).commit())
    }
    fun lastResult(context: Context): String? = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString("result", null)
}

/** One-use in-process handoff. Process death never restores an executable task. */
object PlanHandoff {
    private var pending: TaskPlan? = null
    private var epoch = 0L
    @Synchronized fun put(plan: TaskPlan) { pending = plan; epoch = BridgeEpoch.generation }
    @Synchronized fun take(): TaskPlan? = pending.takeIf { epoch == BridgeEpoch.generation }.also { pending = null }
    @Synchronized fun clear() { pending = null }
}
