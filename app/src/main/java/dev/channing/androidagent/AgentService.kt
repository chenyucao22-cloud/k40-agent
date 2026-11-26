package dev.channing.androidagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentService : AccessibilityService() {
    companion object { var instance: AgentService? = null; private set }
    private val lease = TaskLease()
    private var stopped = true
    private var busy = false
    private var epoch = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onServiceConnected() {
        super.onServiceConnected()
        stopped = false; instance = this
        AuditLog.add(this, AuditLog.Code.SERVICE_ON)
        // No floating control. Stop is available in the app and Android accessibility settings.
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never read event.text, event.source or any private event payload.
        if (SandboxActivity.foreground == null) cancel()
    }
    override fun onInterrupt() { cancel(); SandboxActivity.foreground?.interruptExecution() }
    fun cancel() {
        epoch++; lease.revoke()
        if (busy) AuditLog.add(this, AuditLog.Code.CANCELLED)
        busy = false
    }
    fun emergencyStop() {
        stopped = true; cancel()
        BridgeEpoch.revoke(); SandboxActivity.foreground?.interruptExecution()
        AuditLog.add(this, AuditLog.Code.EMERGENCY_STOP)
        disableSelf()
        if (instance === this) instance = null
    }
    override fun onDestroy() {
        stopped = true; cancel(); mainHandler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        AuditLog.add(this, AuditLog.Code.SERVICE_OFF); super.onDestroy()
    }
    private fun sandboxRoot(): AccessibilityNodeInfo? {
        if (stopped || SandboxActivity.foreground == null) return null
        val root = rootInActiveWindow ?: return null
        if (!SafetyPolicy.allowsPackage(root.packageName?.toString())) { root.recycle(); return null }
        val markers = root.findAccessibilityNodeInfosByViewId(SafetyPolicy.PREFIX + "sandbox_marker")
        val valid = markers.any { it.isVisibleToUser && !it.isPassword }
        markers.forEach { it.recycle() }
        if (!valid) { root.recycle(); return null }
        return root
    }
    fun execute(action: Action, report: (Boolean, String) -> Unit) {
        if (busy || stopped || action.opensApp) {
            AuditLog.add(this, AuditLog.Code.BLOCKED); report(false, "任务被拒绝"); return
        }
        val root = sandboxRoot()
        if (root == null) { AuditLog.add(this, AuditLog.Code.BLOCKED); report(false, "请保持测试界面在前台"); return }
        val windowId = root.windowId
        lease.arm(action, windowId, SystemClock.elapsedRealtime())
        if (!lease.consume(action, root.windowId, SystemClock.elapsedRealtime())) {
            root.recycle(); report(false, "任务授权已失效"); return
        }
        busy = true
        val ticket = ++epoch
        fun finish(ok: Boolean, code: AuditLog.Code, message: String) {
            if (ticket != epoch || stopped) return
            busy = false; lease.revoke()
            AuditLog.add(this, if(ok) code else AuditLog.Code.ACTION_FAILED)
            report(ok, if(ok) message else "操作失败，请检查测试控件是否可见")
        }
        try {
            if (action == Action.INSPECT) {
                // Query only known IDs. No traversal, screenshots, node text, hints or descriptions.
                val found = SafetyPolicy.readableIds.filter { shortId ->
                    val nodes = root.findAccessibilityNodeInfosByViewId(SafetyPolicy.PREFIX + shortId)
                    val visible = nodes.any { allowed(it) }
                    nodes.forEach { it.recycle() }; visible
                }
                finish(true, AuditLog.Code.INSPECT_OK, "识别控件 ID：\n" + found.joinToString("\n"))
                return
            }
            val nodes = root.findAccessibilityNodeInfosByViewId(SafetyPolicy.PREFIX + action.target)
            try {
                val node = nodes.singleOrNull()?.takeIf { allowed(it) && it.isEnabled }
                if (node == null) { finish(false, AuditLog.Code.ACTION_FAILED, ""); return }
                when (action) {
                    Action.CLICK -> finish(node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                        AuditLog.Code.CLICK_OK, "已点击测试按钮")
                    Action.INPUT -> {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, SafetyPolicy.DEMO_TEXT)
                        }
                        finish(node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args),
                            AuditLog.Code.INPUT_OK, "已输入固定测试文字")
                    }
                    Action.SWIPE -> {
                        val bounds = Rect(); node.getBoundsInScreen(bounds)
                        // The entire gesture remains inside a visible scroll container.
                        if (bounds.width() < 40 || bounds.height() < 80 || !node.isScrollable) {
                            finish(false, AuditLog.Code.ACTION_FAILED, ""); return
                        }
                        val x = bounds.centerX().toFloat()
                        val path = Path().apply {
                            moveTo(x, bounds.top + bounds.height() * .75f)
                            lineTo(x, bounds.top + bounds.height() * .25f)
                        }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build()
                        val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                finish(true, AuditLog.Code.SWIPE_OK, "滑动手势已完成；请检查测试行位置")
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                finish(false, AuditLog.Code.ACTION_FAILED, "")
                            }
                        }, null)
                        if (!submitted) finish(false, AuditLog.Code.ACTION_FAILED, "")
                        // Clear stale busy state even if Android fails to deliver a callback.
                        mainHandler.postDelayed({
                            if (ticket == epoch && busy) { cancel(); report(false, "手势回调超时，任务已撤销") }
                        }, 2000)
                    }
                    Action.INSPECT, Action.OPEN_QQ, Action.TIMER_5_MIN, Action.OPEN_APPS -> Unit
                }
            } finally { nodes.forEach { it.recycle() } }
        } catch (_: RuntimeException) {
            finish(false, AuditLog.Code.ACTION_FAILED, "")
        } finally { root.recycle() }
    }
    private fun allowed(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser && SafetyPolicy.allowsPackage(node.packageName?.toString()) &&
            SafetyPolicy.allowsId(node.viewIdResourceName, node.isPassword)
}
