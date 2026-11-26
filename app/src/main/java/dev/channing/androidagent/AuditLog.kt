package dev.channing.androidagent

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only enum codes, never node text, user input, message content or exceptions. */
object AuditLog {
    enum class Code {
        SERVICE_ON, SERVICE_OFF, CONSENT, DECLINED, INSPECT_OK, CLICK_OK, INPUT_OK,
        SWIPE_OK, ACTION_FAILED, BLOCKED, CANCELLED, EMERGENCY_STOP,
        PLAN_ACCEPTED, PLAN_REJECTED, PLAN_CANCELLED, PLAN_COMPLETE, BRIDGE_FETCH, BRIDGE_REPORT,
        SEND_REVIEW, SEND_CANCELLED, SEND_LOCAL_ONLY
    }
    @Synchronized fun add(context: Context, code: Code) {
        val prefs = context.getSharedPreferences("audit", Context.MODE_PRIVATE)
        val lines = prefs.getString("entries", "").orEmpty().lines().filter { it.isNotBlank() }
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        prefs.edit().putString("entries", (lines + "$time  ${code.name}").takeLast(200).joinToString("\n")).apply()
    }
    fun read(context: Context) = context.getSharedPreferences("audit", Context.MODE_PRIVATE)
        .getString("entries", "暂无日志").orEmpty()
    fun clear(context: Context) = context.getSharedPreferences("audit", Context.MODE_PRIVATE).edit().clear().apply()
}
