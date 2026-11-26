package dev.channing.androidagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import java.util.concurrent.Executors

class BridgeActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private var resumed = false
    private var busy = false
    private var client: BridgeClient? = null
    private var plan: TaskPlan? = null
    private var planEpoch = 0L
    private var appConfirmation: AlertDialog? = null
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private lateinit var origin: EditText
    private lateinit var token: EditText
    private lateinit var json: EditText
    private lateinit var pairing: EditText
    private lateinit var credentials: BridgeCredentials
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); secureWindow()
        credentials = BridgeCredentials(this)
        val body = column()
        body.label("任务桥接 · 0.5.0", 25f)
        body.label("任务在手机上确认后执行。支持测试页、打开 QQ 和向系统时钟提交 5 分钟倒计时。", 15f)
        body.label("本机设备 ID：\n${PlanStore.deviceId(this)}", 13f).setTextIsSelectable(true)
        body.button("复制本机设备 ID") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Android Agent device ID", PlanStore.deviceId(this)))
            if (::status.isInitialized) status.text = "设备 ID 已复制。可粘贴到私有控制台；不要复制或分享配对令牌到聊天。"
        }
        body.button("打开私有任务控制台") {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://android-agent-console-cao.flashybud64.chatgpt.site"))) }
            catch (_: Exception) { if (::status.isInitialized) status.text = "请从本次对话链接在浏览器打开控制台。" }
        }
        status = body.label("尚未连接；无需填写任何 OpenAI 账号密码。")
        body.button("从桥接服务拉取一条计划") { network(false) }
        body.button("生成并预览本机演示计划") {
            if (!busy) importPlan(PlanCodec.demo(PlanStore.deviceId(this), now()))
        }
        json = EditText(this).apply {
            hint = "粘贴任务 JSON 或动作数组，如 [\"INSPECT\",\"CLICK\"]"; minLines = 3; maxLines = 6
            filters = arrayOf(InputFilter.LengthFilter(PlanCodec.MAX_BYTES))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }; body.addView(json)
        body.button("校验并导入 JSON") { if (!busy) importPlan(json.text.toString()) }
        preview = body.label("无待执行计划", 15f)
        body.button("确认并执行此计划") {
            val task = plan
            if (!busy && task != null && task.isCurrent(now()) && planEpoch == BridgeEpoch.generation) {
                val action = task.actions.singleOrNull()
                if (action?.opensApp == true) {
                    if (action == Action.OPEN_APPS) chooseApp(task) else confirmAppAction(task, action)
                } else {
                    PlanHandoff.put(task); plan = null
                    startActivity(Intent(this, SandboxActivity::class.java).putExtra("bridge_plan", true))
                }
            } else status.text = "请先导入有效计划；过期计划需重新生成。"
        }
        body.label("连接私有控制台", 19f)
        body.label("在控制台输入上方本机设备 ID，生成配置，再粘贴到这里。配置含专用访问凭据，请勿发到聊天。", 14f)
        pairing = EditText(this).apply {
            hint = "粘贴控制台生成的完整配对配置"; minLines = 2; maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(4096))
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }; body.addView(pairing)
        body.button("校验并保存控制台配对配置") {
            if (!busy) try {
                val config = PairingCodec.parse(pairing.text.toString(), PlanStore.deviceId(this), now())
                AlertDialog.Builder(this).setTitle("确认连接控制台")
                    .setMessage("仅连接：${config.origin}\n配对有效至 ${java.util.Date(config.expiresAt * 1000)}\n只会拉取固定测试计划及手动上传结果；保存不会执行任务。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存此连接") { _, _ ->
                        try {
                            credentials.save(config.origin, config.deviceToken, config.siteToken)
                            pairing.text.clear(); origin.setText(config.origin); token.text.clear()
                            plan = null; preview.text = "无待执行计划"; status.text = "控制台已配对。请先在控制台加入测试任务，再手动拉取。"
                        } catch (_: Exception) { status.text = "配置保存失败，未连接。" }
                    }.show().also { it.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true }
            } catch (_: Exception) { status.text = "配对配置被拒绝，请检查格式、本机设备 ID 和有效期。" }
        }
        body.label("可选：自建 HTTPS 服务", 19f)
        body.label("未部署服务时保持空白。配对令牌只用于本项目；不要填 ChatGPT session、Cookie、账号密码或 API key。", 14f)
        origin = EditText(this).apply {
            hint = "https://你的桥接域名（仅域名和可选端口）"; setSingleLine(true)
            setText(credentials.origin()); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }; body.addView(origin)
        token = EditText(this).apply {
            hint = "专用设备配对令牌"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }; body.addView(token)
        body.button("保存 HTTPS 配对配置") {
            if (!busy) try {
                credentials.save(origin.text.toString(), token.text.toString())
                token.text.clear(); plan = null; preview.text = "无待执行计划"
                status.text = "配置已加密保存；尚未发送网络请求。"
            } catch (_: Exception) { status.text = "保存失败。需有效 HTTPS 地址和至少 43 位专用随机令牌。" }
        }
        body.button("预览并上传最后一条执行结果") {
            val receipt = PlanStore.lastResult(this)
            if (receipt == null) status.text = "暂无执行结果"
            else AlertDialog.Builder(this).setTitle("确认上传结果")
                .setMessage("目标：${credentials.origin()}\n仅上传任务 ID、状态代码和完成步数，不上传界面或输入内容：\n$receipt")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认上传") { _, _ -> network(true) }.show().also {
                    it.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
                }
        }
        body.button("停止通信并删除本机配对配置") {
            cancelNetwork(); credentials.clear(); token.text.clear(); pairing.text.clear(); origin.text.clear()
            plan = null; PlanHandoff.clear(); preview.text = "无待执行计划"
            status.text = "通信已停止，配对配置已删除。服务端撤销请按说明轮换令牌。"
        }
        body.button("紧急停止所有任务和通信") {
            cancelNetwork(); plan = null; BridgeEpoch.revoke(); AgentService.instance?.emergencyStop()
            preview.text = "无待执行计划"; status.text = "已停止"
        }
        setContentView(ScrollView(this).apply { addView(body) })
    }
    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() {
        resumed = false; cancelNetwork(); plan = null
        appConfirmation?.dismiss()
        if (::pairing.isInitialized) pairing.text.clear()
        if (::token.isInitialized) token.text.clear()
        if (::preview.isInitialized) preview.text = "返回后需重新导入计划；已执行结果可手动上传。"
        super.onPause()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
    private fun now() = System.currentTimeMillis() / 1000
    private fun cancelNetwork() { generation++; client?.cancel(); client = null; busy = false }
    private fun canExecute(task: TaskPlan): Boolean =
        resumed && !busy && plan === task && planEpoch == BridgeEpoch.generation && task.isCurrent(now())

    private fun chooseApp(task: TaskPlan) {
        val apps = AppActions.launchableApps(this)
        if (apps.isEmpty()) {
            status.text = "Android 没有返回可打开的应用。"
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择要打开的应用")
            .setItems(apps.map { it.name }.toTypedArray()) { _, index ->
                if (canExecute(task)) {
                    val app = apps[index]
                    confirmAppAction(task, Action.OPEN_APPS, app)
                }
            }
            .setNegativeButton("取消", null)
            .show()
        trackConfirmation(dialog)
    }

    private fun confirmAppAction(task: TaskPlan, action: Action, app: AppActions.Launchable? = null) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("确认一次性操作")
            .setMessage(app?.let { "打开 ${it.name}" } ?: action.title)
            .setNegativeButton("取消", null)
            .setPositiveButton("在手机上执行") { _, _ ->
                if (!canExecute(task)) {
                    status.text = "任务已过期或取消，请重新拉取。"
                } else {
                    plan = null
                    preview.text = "无待执行计划"
                    val outcome = AppActions.execute(this, action, app)
                    PlanStore.result(this, task, if (outcome.accepted) 1 else 0,
                        if (outcome.accepted) "COMPLETE" else "FAILED")
                    AuditLog.add(this, if (outcome.accepted) AuditLog.Code.PLAN_COMPLETE else AuditLog.Code.PLAN_CANCELLED)
                    status.text = outcome.message
                }
            }.show()
        trackConfirmation(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
    }

    private fun trackConfirmation(dialog: AlertDialog) {
        appConfirmation = dialog
        dialog.setOnDismissListener { if (appConfirmation === dialog) appConfirmation = null }
    }

    private fun importPlan(raw: String, allowSuggestions: Boolean = true) {
        plan = null; preview.text = "无待执行计划"
        try {
            val task = if (allowSuggestions && raw.trimStart().startsWith("["))
                PlanCodec.suggestions(raw, PlanStore.deviceId(this), now())
            else PlanCodec.parse(raw, PlanStore.deviceId(this), now())
            PlanStore.accept(this, task, now()); plan = task; planEpoch = BridgeEpoch.generation; json.text.clear()
            preview.text = "任务 ${task.taskId}\n有效至 ${java.util.Date(task.expiresAt * 1000)}\n${task.summary()}"
            status.text = "计划已校验。请阅读后点“确认并执行此计划”。"
        } catch (_: Exception) {
            AuditLog.add(this, AuditLog.Code.PLAN_REJECTED)
            status.text = "任务被拒绝：请检查格式、设备 ID、有效期、重复 ID/nonce 和动作白名单。"
        }
    }
    private fun network(upload: Boolean) {
        if (busy || !resumed) return
        val config = try { credentials.load() } catch (_: Exception) { null }
        if (config == null) { status.text = "请先配置自己的 HTTPS 桥接服务，或使用本机演示计划。"; return }
        val receipt = if (upload) PlanStore.lastResult(this) ?: return else null
        val ticket = ++generation; busy = true
        val transport = BridgeClient(); client = transport
        plan = null; preview.text = "无待执行计划"; status.text = "正在进行一次 HTTPS 请求…"
        val id = PlanStore.deviceId(this)
        executor.execute {
            try {
                val raw = transport.request(config, id, receipt)
                runOnUiThread {
                    if (ticket == generation && resumed) {
                        busy = false; client = null
                        AuditLog.add(this, if(upload) AuditLog.Code.BRIDGE_REPORT else AuditLog.Code.BRIDGE_FETCH)
                        if (upload) status.text = "结果回执已提交；本机保留记录。"
                        else if (raw == null) status.text = "服务端暂无任务。"
                        else importPlan(raw, false)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (ticket == generation && resumed) {
                        busy = false; client = null
                        status.text = "通信失败，未执行任务。请检查网络、HTTPS 证书、设备配对和令牌有效期。不会自动重试。"
                    }
                }
            }
        }
    }
}
