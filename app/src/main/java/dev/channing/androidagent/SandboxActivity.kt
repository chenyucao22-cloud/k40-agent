package dev.channing.androidagent

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.*

/** Only an internal handoff can supply a validated sandbox task. */
class SandboxActivity : Activity() {
    companion object { var foreground: SandboxActivity? = null; private set }
    private lateinit var status: TextView
    private var consentDialog: AlertDialog? = null
    private var pending = false
    private var clicks = 0
    private var generation = 0L
    private var task: TaskPlan? = null
    private var completedSteps = 0
    private var deadline = 0L
    private lateinit var nextStep: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); secureWindow()
        val page = column()
        page.label("独立测试界面", 24f).id = R.id.sandbox_marker
        page.label("每次动作均需确认。请勿在测试输入框填入真实个人信息。", 14f)
        page.button("紧急停止并关闭服务") { stop() }
        status = page.label("等待任务", 14f).apply { maxLines = 3 }
        task = if (savedInstanceState == null && intent.getBooleanExtra("bridge_plan", false)) PlanHandoff.take() else null
        val planControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.addView(planControls)
        nextStep = planControls.button("确认计划下一步") {
            val plan = task
            if (plan == null) status.text = "无待执行计划"
            else if (!validPlan()) abortPlan("EXPIRED")
            else request(plan.actions[completedSteps], true)
        }
        planControls.button("取消当前计划") { AgentService.instance?.cancel(); interruptExecution() }
        for (i in 0 until planControls.childCount) planControls.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        task?.let { plan ->
            deadline = SystemClock.elapsedRealtime() + maxOf(0, plan.expiresAt * 1000 - System.currentTimeMillis())
            status.text = "计划共 ${plan.actions.size} 步；第一步：${plan.actions.first().title}\n请点击“确认计划下一步”。"
        }
        nextStep.isEnabled = task != null
        val row = LinearLayout(this)
        Action.entries.filterNot(Action::opensApp).forEach { action ->
            row.addView(Button(this).apply {
                text = when(action) { Action.INSPECT -> "识别"; Action.CLICK -> "点击"; Action.INPUT -> "输入"; Action.SWIPE -> "滑动"; Action.OPEN_QQ, Action.TIMER_5_MIN, Action.OPEN_APPS -> error("Launcher actions do not belong on the test page") }
                filterTouchesWhenObscured = true
                setOnClickListener { request(action) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(row)
        page.button("测试按钮：已点击 0 次") {
            clicks++; (findViewById<Button>(R.id.demo_click)).text = "测试按钮：已点击 $clicks 次"
        }.id = R.id.demo_click
        page.addView(EditText(this).apply {
            id = R.id.demo_input; hint = "普通测试输入框"; setSingleLine(true)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        })
        page.button("模拟发送（人工确认，无网络）") { reviewSend() }.id = R.id.demo_send
        // Sensitive fields are outside the service's ID allowlist. Never access their text.
        val sensitive = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sensitive.addView(EditText(this).apply {
            id = R.id.sensitive_password; hint = "密码保护测试"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sensitive.addView(EditText(this).apply {
            id = R.id.sensitive_otp; hint = "验证码保护测试"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(sensitive)
        page.addView(ScrollView(this).apply {
            id = R.id.demo_scroll
            addView(column().apply { repeat(40) { label("可滑动的测试行 ${it + 1}") } })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }
    override fun onResume() { super.onResume(); foreground = this }
    override fun onPause() {
        abortPlan("CANCELLED"); generation++; foreground = null; pending = false; consentDialog?.dismiss(); consentDialog = null
        AgentService.instance?.cancel(); super.onPause()
    }
    fun interruptExecution() {
        abortPlan("CANCELLED"); generation++; pending = false
        consentDialog?.dismiss(); consentDialog = null
        status.text = "执行已撤销；请重新确认新任务。"
    }
    private fun validPlan(): Boolean = task?.isCurrent(System.currentTimeMillis() / 1000) == true && SystemClock.elapsedRealtime() < deadline
    fun abortPlan(code: String) {
        val plan = task ?: return
        task = null; generation++; pending = false; nextStep.isEnabled = false
        consentDialog?.dismiss(); consentDialog = null
        PlanStore.result(this, plan, completedSteps, code)
        AuditLog.add(this, if (code == "COMPLETE") AuditLog.Code.PLAN_COMPLETE else AuditLog.Code.PLAN_CANCELLED)
        status.text = "计划结果：$code，已完成 $completedSteps/${plan.actions.size} 步；不会自动继续。"
    }
    private fun request(action: Action, fromPlan: Boolean = false) {
        if (task != null && !fromPlan) { status.text = "请先完成或取消当前计划"; return }
        if (fromPlan && !validPlan()) { abortPlan("EXPIRED"); return }
        if (pending || action.opensApp) return
        val service = AgentService.instance
        if (service == null) { if (fromPlan) abortPlan("FAILED"); status.text = "请先在系统设置开启无障碍服务"; return }
        pending = true
        var approved = false
        val ticket = ++generation
        consentDialog = AlertDialog.Builder(this).setTitle("确认一次性任务")
            .setMessage("范围：本测试界面\n动作：${action.title}\n输入内容仅为预设测试文字；不读取输入框内容。确认只对本次操作有效。")
            .setNegativeButton("取消") { _, _ -> AuditLog.add(this, AuditLog.Code.DECLINED) }
            .setPositiveButton("确认执行一次") { _, _ ->
                approved = true
                AuditLog.add(this, AuditLog.Code.CONSENT)
                // Wait for the confirmation window to disappear; revalidate on execution.
                status.postDelayed({
                    if (foreground === this && ticket == generation) {
                        if (fromPlan && !validPlan()) abortPlan("EXPIRED")
                        else service.execute(action) { ok, result ->
                            if (ticket == generation && foreground === this) {
                                pending = false; status.text = result
                                if (fromPlan) {
                                    if (!ok) abortPlan("FAILED")
                                    else {
                                        completedSteps++
                                        val plan = task
                                        if (plan != null && completedSteps == plan.actions.size) abortPlan("COMPLETE")
                                        else status.text = "$result\n已完成 $completedSteps 步，请手动确认下一步。"
                                    }
                                }
                            }
                        }
                    }
                }, 350)
            }.create().apply {
                setOnCancelListener { AuditLog.add(this@SandboxActivity, AuditLog.Code.DECLINED) }
                setOnDismissListener {
                    if (!approved) { pending = false; if (fromPlan) abortPlan("CANCELLED") }
                }; show()
                getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
            }
    }
    private fun reviewSend() {
        AgentService.instance?.cancel(); interruptExecution()
        AuditLog.add(this, AuditLog.Code.SEND_REVIEW)
        consentDialog = AlertDialog.Builder(this).setTitle("人工确认模拟发送")
            .setMessage("收件人：本机测试接收器\n内容：${SafetyPolicy.DEMO_TEXT}\n\n本版只记录本地发送结果，不会读取输入框或发送任何网络消息。")
            .setNegativeButton("取消") { _, _ -> AuditLog.add(this, AuditLog.Code.SEND_CANCELLED) }
            .setPositiveButton("本人确认模拟发送") { _, _ ->
                if (foreground === this) {
                    AuditLog.add(this, AuditLog.Code.SEND_LOCAL_ONLY)
                    status.text = "本地模拟发送完成（未联网）"
                }
            }.create().apply {
                setOnCancelListener { AuditLog.add(this@SandboxActivity, AuditLog.Code.SEND_CANCELLED) }
                show(); getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
            }
    }
    private fun stop() {
        abortPlan("CANCELLED"); generation++; pending = false; consentDialog?.dismiss(); AgentService.instance?.emergencyStop()
        status.text = "已停止；重新操作需在系统设置重新授权服务"
    }
}
