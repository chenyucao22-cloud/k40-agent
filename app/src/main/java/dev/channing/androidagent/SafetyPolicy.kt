package dev.channing.androidagent

/** Pure policy: cloud/model output cannot widen this allowlist. */
object SafetyPolicy {
    const val PACKAGE = "dev.channing.androidagent"
    const val PREFIX = "$PACKAGE:id/"
    const val DEMO_TEXT = "Android Agent 测试文字"
    val readableIds = setOf("sandbox_marker", "demo_click", "demo_input", "demo_scroll")
    fun allowsPackage(name: String?) = name == PACKAGE
    fun allowsId(id: String?, password: Boolean): Boolean =
        !password && id != null && id.startsWith(PREFIX) && id.removePrefix(PREFIX) in readableIds
    fun permits(action: Action, target: String?): Boolean = target == action.target
    fun permitsPlan(actions: List<Action>): Boolean =
        actions.size in 1..10 && (actions.size == 1 || actions.none(Action::opensApp))
}
enum class Action(val title: String, val target: String?, val opensApp: Boolean = false) {
    INSPECT("识别测试控件（不读取文字）", null),
    CLICK("点击测试按钮", "demo_click"),
    INPUT("输入固定测试文字", "demo_input"),
    SWIPE("向上滑动测试列表", "demo_scroll"),
    OPEN_QQ("打开 QQ 首页", "com.tencent.mobileqq", opensApp = true),
    TIMER_5_MIN("把 5 分钟倒计时交给系统时钟", "com.android.deskclock", opensApp = true),
    OPEN_APPS("在手机上选择并打开应用", null, opensApp = true)
}

/** Monotonic time, one-use capability, bound to one Android window. */
class TaskLease {
    private var action: Action? = null
    private var window = -1
    private var expires = 0L
    fun arm(value: Action, windowId: Int, now: Long) {
        action = value; window = windowId; expires = now + 15_000
    }
    fun consume(value: Action, windowId: Int, now: Long): Boolean {
        val ok = action == value && window == windowId && now < expires
        revoke()
        return ok
    }
    fun revoke() { action = null; window = -1; expires = 0 }
}
