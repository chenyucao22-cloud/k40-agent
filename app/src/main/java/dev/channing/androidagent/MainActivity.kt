package dev.channing.androidagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.channing.androidagent.local.LocalNative
import dev.channing.androidagent.local.LocalTask
import dev.channing.androidagent.local.LocalTasks
import dev.channing.androidagent.local.Models
import dev.channing.androidagent.local.Speech
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var active: AtomicBoolean? = null
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("就绪")
    private var text by mutableStateOf("")
    private var task by mutableStateOf<LocalTask?>(null)
    private var ready by mutableStateOf(false)
    private var settings by mutableStateOf(false)
    private val tuning by lazy { getSharedPreferences("local_tuning", MODE_PRIVATE) }
    private var voiceFeedback by mutableStateOf(false)
    private var tts: TextToSpeech? = null
    private val history = mutableStateListOf<String>()
    private val record by lazy { getSharedPreferences("assistant_history", MODE_PRIVATE) }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) listen()
        else if (!it) status = "未授予录音权限，可用键盘输入"
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background("正在导入模型") { stop ->
            contentResolver.openInputStream(uri)?.use { stream ->
                Models.install(this, stream, stop::get, ::progress)
            } ?: error("无法读取所选文件")
            "模型已安装"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        if (openReview(intent)) return
        if (intent.action == Intent.ACTION_ASSIST && this !is QuickActivity) {
            startActivity(Intent(this, QuickActivity::class.java))
            finish()
            return
        }
        history.addAll(record.getString("status", "").orEmpty().lines().filter(String::isNotBlank).take(20))
        ready = Models.file(this).length() == Models.SIZE
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { Assistant() }
            }
        }
    }

    private fun openReview(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW || intent.dataString != "androidagent://review") return false
        startActivity(Intent(this, BridgeActivity::class.java))
        finish()
        return true
    }

    override fun onStop() {
        super.onStop()
        // The visible activity owns microphone capture and model jobs.
        cancelActive()
    }
    override fun onDestroy() {
        cancelActive()
        worker.shutdown()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN) cancelActive("内存紧张，已请求停止")
    }
    override fun onLowMemory() {
        super.onLowMemory()
        cancelActive("内存紧张，已请求停止")
    }

    private fun cancelActive(message: String = "正在停止") {
        active?.let {
            it.set(true)
            BridgeClient.cancelAll()
            runCatching { LocalNative.abort() }
            status = message
        }
    }

    private fun background(label: String, block: (AtomicBoolean) -> String) {
        if (busy || AssistantEntrypoints.stopWork != null) return
        val stop = AtomicBoolean(false)
        active = stop
        busy = true
        status = label
        AssistantEntrypoints.stopWork = { cancelActive() }
        AssistantEntrypoints.status(this, label)
        worker.execute {
            val result = runCatching { block(stop) }.getOrElse {
                when (it) {
                    is OutOfMemoryError -> "可用内存不足，请退出其他应用后再试"
                    is LinkageError -> "本地运行库未能加载"
                    else -> it.message?.takeIf { msg -> msg in setOf("无法读取所选文件","模型校验失败","文件大小不符","模型保存失败","下载失败") }
                        ?: "任务未完成，请检查模型、存储或权限"
                }
            }
            runOnUiThread {
                AssistantEntrypoints.stopWork = null
                AssistantEntrypoints.status(this, null)
                if (isDestroyed) return@runOnUiThread
                status = if (stop.get()) "已取消" else result
                busy = false
                active = null
                ready = Models.file(this).length() == Models.SIZE
            }
        }
    }

    private fun progress(value: Int) = runOnUiThread {
        if (!isDestroyed && active?.get() == false) status = if (value == 100) "正在校验模型" else "正在传输模型 $value%"
    }

    private fun prepare() {
        if (busy) return
        val input = text.trim()
        if (input.isEmpty()) return
        task = null
        LocalTasks.rules(input)?.let {
            if (LocalTasks.valid(it)) { task = it; status = "请核对任务" } else status = "请检查目标和内容"
            return
        }
        if (!ready) { status = "可先试「打开微信」。复杂指令需要安装本地模型"; return }
        background("正在本地解析") { stop ->
            LocalNative.reset()
            if (stop.get()) return@background "已取消"
            val memory = android.app.ActivityManager.MemoryInfo()
            getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
            if (memory.lowMemory || memory.availMem < 800L * 1024 * 1024) return@background "可用内存不足，请退出其他应用后再试"
            val parsed = runCatching {
                val raw = LocalNative.generate(Models.file(this).absolutePath.toByteArray(), input.toByteArray(), tuning.getInt("threads", 4).coerceIn(2, 4))
                    ?: error("解析失败")
                LocalTasks.model(raw.decodeToString(throwOnInvalidSequence = true), input)
            }.getOrNull() ?: return@background "这条指令无法可靠解析，请说清目标或使用快捷入口"
            runOnUiThread { if (!stop.get() && !isDestroyed) task = parsed }
            "请核对任务"
        }
    }

    private fun voice() {
        if (busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun listen() {
        task = null
        background("正在听，最多 15 秒") { stop ->
            val words = Speech.listen(this, stop::get) { partial ->
                runOnUiThread { if (!stop.get() && !isDestroyed) text = partial.take(256) }
            }
            if (words.isBlank()) return@background "未听清，请再试一次"
            runOnUiThread { if (!stop.get() && !isDestroyed) text = words.take(256) }
            "识别完成，核对文字后点准备"
        }
    }

    private fun speak(message: String) {
        val engine = tts ?: return
        val offline = engine.voices?.firstOrNull { it.locale.language == "zh" && !it.isNetworkConnectionRequired } ?: return
        if (engine.setVoice(offline) == TextToSpeech.SUCCESS) engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "feedback")
    }

    @Composable private fun Assistant() {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (this@MainActivity is QuickActivity) "快速助手" else "Android Agent",
                style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(text, { text = it.take(256); task = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text("说出或输入任务") }, placeholder = { Text("例如：给张三发 QQ，说我马上到") }, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::prepare, enabled = !busy && text.isNotBlank()) { Text("准备") }
                OutlinedButton(onClick = ::voice, enabled = !busy) { Text("语音输入") }
                if (busy) TextButton(onClick = { cancelActive() }) { Text("停止") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, color = MaterialTheme.colorScheme.primary)
            task?.let { planned ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(planned.label())
                        Button(enabled = !busy, onClick = {
                            task = null
                            val result = LocalTasks.dispatch(this@MainActivity, planned)
                            status = result
                            history.add(0, "${planned.action} · $result")
                            if (history.size > 20) history.removeAt(history.lastIndex)
                            record.edit().putString("status", history.joinToString("\n")).apply()
                            if (voiceFeedback) speak(result)
                        }) { Text(if (planned.action == "draft") "复制正文并打开应用" else "确认执行") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { if (!busy) { text = "打开微信"; prepare() } }, label = { Text("微信") })
                AssistChip(onClick = { if (!busy) { text = "打开QQ"; prepare() } }, label = { Text("QQ") })
                AssistChip(onClick = { if (!busy) { text = "打开蓝牙设置"; prepare() } }, label = { Text("蓝牙") })
            }
            if (history.isNotEmpty()) {
                Text("最近任务", style = MaterialTheme.typography.titleMedium)
                history.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (this@MainActivity !is QuickActivity) TextButton(onClick = { settings = !settings }) { Text("模型、隐私与权限") }
            if (settings) {
                Text("语音仅在你主动点击后录制并在本机识别。复杂指令由本机 Qwen 解析。")
                TextButton(onClick = { history.clear(); record.edit().remove("status").apply() }) { Text("清除历史") }
                Text(if (ready) "Qwen2.5-1.5B Q4_K_M 已就绪" else "Qwen2.5-1.5B Q4_K_M · 初次下载约 1.12 GB")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && !ready, onClick = {
                        background("正在下载，可停止后续传") { stop ->
                            Models.install(this@MainActivity, null, stop::get, ::progress)
                            "模型已安装"
                        }
                    }) { Text("下载模型") }
                    OutlinedButton(enabled = !busy, onClick = { importModel.launch(arrayOf("*/*")) }) { Text("导入") }
                }
                TextButton(enabled = !busy, onClick = {
                    voiceFeedback = !voiceFeedback
                    if (voiceFeedback && tts == null) tts = TextToSpeech(this@MainActivity) { }
                }) { Text(if (voiceFeedback) "关闭播报" else "开启本机中文播报") }
                if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 55)
                }) { Text("开启任务通知") }
                if (Build.VERSION.SDK_INT >= 29) TextButton(onClick = {
                    val role = getSystemService(android.app.role.RoleManager::class.java)
                    if (role.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) &&
                        !role.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                        startActivityForResult(role.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT), 56)
                    } else status = "请在系统的默认应用设置检查数字助手"
                }) { Text("设为系统数字助手") }
                TextButton(enabled = !busy, onClick = {
                    background("正在同步配置") { stop ->
                        val credentials = BridgeCredentials(this@MainActivity).load() ?: error("未配对")
                        val json = BridgeClient().syncConfig(credentials, PlanStore.deviceId(this@MainActivity))
                        val config = org.json.JSONObject(json)
                        val threadValue = config.get("threads")
                        require(config.length() == 4 && config.getInt("schema") == 1 && config.getInt("context") == 2048 &&
                            config.getString("model") == "qwen2.5-1.5b-q4_k_m" && threadValue is Int && threadValue in 2..4)
                        if (stop.get()) return@background "已取消"
                        tuning.edit().putInt("threads", threadValue).apply()
                        "已同步本地模型配置"
                    }
                }) { Text("同步高级配置") }
                TextButton(onClick = { startActivity(Intent(this@MainActivity, BridgeActivity::class.java)) }) {
                    Text("高级设备桥接")
                }
                TextButton(onClick = {
                    cancelActive()
                    BridgeEpoch.revoke()
                    AgentService.instance?.emergencyStop()
                    task = null
                }) { Text("紧急停止并关闭无障碍服务") }
            }
        }
    }
}

class QuickActivity : MainActivity()
