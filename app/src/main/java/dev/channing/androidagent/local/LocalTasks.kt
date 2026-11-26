package dev.channing.androidagent.local

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

data class LocalTask(val action: String, val target: String, val message: String = "", val app: String = "") {
    fun label(): String = when (action) {
        "open" -> "打开 $target"
        "navigate" -> "查看前往 $target 的路线"
        "search" -> "搜索 $target"
        "settings" -> "打开 ${if (target == "wifi") "Wi-Fi" else "蓝牙"}设置"
        "draft" -> "$app 草稿 · 收件人 $target\n$message"
        else -> "请补充任务信息"
    }
}

object LocalTasks {
    private val apps = mapOf("微信" to "com.tencent.mm", "QQ" to "com.tencent.mobileqq")
    fun rules(input: String): LocalTask? {
        val text = input.trim()
        Regex("^(?:帮我)?打开\\s*(Wi-?Fi|无线网络|蓝牙)设置[。]?$", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return LocalTask("settings", if (it.groupValues[1] == "蓝牙") "bluetooth" else "wifi")
        }
        Regex("^(?:请|帮我|请帮我)?打开\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            val name = it.groupValues[1].trimEnd('。', '！', '!').trim()
            return LocalTask("open", if (name.equals("qq", true)) "QQ" else name)
        }
        Regex("^(?:帮我)?(导航到|导航去|搜索|搜一下)\\s*(.+)$").matchEntire(text)?.let {
            return LocalTask(if (it.groupValues[1].startsWith("导航")) "navigate" else "search", it.groupValues[2].trimEnd('。'))
        }
        Regex("^(?:帮我)?给(.+?)发\\s*(QQ|微信)(?:消息)?[，,：:]\\s*(?:说)?([\\s\\S]+)$", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            return LocalTask("draft", it.groupValues[1].trim(), it.groupValues[3],
                if (it.groupValues[2].equals("qq", true)) "QQ" else "微信")
        }
        return null
    }

    fun model(text: String, userText: String): LocalTask {
        require(text.length <= 800)
        val values = mutableMapOf<String, String>()
        JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }.use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(key in setOf("action", "target", "message", "app") && !values.containsKey(key))
                require(reader.peek() == JsonToken.STRING)
                values[key] = reader.nextString()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && values.size == 4)
        }
        val task = LocalTask(values.getValue("action"), values.getValue("target"), values.getValue("message"), values.getValue("app"))
        require(task.action == "draft" || (task.app.isEmpty() && task.message.isEmpty()))
        require(valid(task) && when(task.action) {
            "search", "navigate" -> userText.contains(task.target)
            "open" -> userText.contains(task.target, ignoreCase = true)
            "draft" -> userText.contains(task.target) && userText.contains(task.message) && userText.contains(task.app, true)
            "settings" -> if (task.target == "wifi") Regex("wi-?fi|无线|网络", RegexOption.IGNORE_CASE).containsMatchIn(userText)
                else userText.contains("蓝牙")
            else -> false
        })
        return task
    }

    fun valid(task: LocalTask): Boolean = task.target.isNotBlank() && task.target.length <= 140 &&
        when (task.action) {
            "open" -> task.target.length <= 60 && task.target.none { it.isISOControl() }
            "settings" -> task.target in setOf("wifi", "bluetooth")
            "search", "navigate" -> true
            "draft" -> task.app in apps && task.message.isNotBlank() && task.message.length <= 500
            else -> false
        }

    fun dispatch(activity: Activity, task: LocalTask): String {
        require(valid(task))
        val intent = when (task.action) {
            "open" -> {
                val pm = activity.packageManager
                val known = apps[task.target]?.let(pm::getLaunchIntentForPackage)
                if (known != null) known else {
                    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val matches = pm.queryIntentActivities(launcher, 0).filter {
                        it.activityInfo.exported && it.loadLabel(pm).toString().equals(task.target, true)
                    }.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.distinct()
                    if (matches.size != 1) return if (matches.isEmpty()) "找不到这个应用，请输入桌面上的完整名称"
                        else "找到多个同名应用，请从手机桌面选择"
                    launcher.setComponent(matches.single())
                }
            }
            "draft" -> activity.packageManager.getLaunchIntentForPackage(apps.getValue(task.app))
                ?: return "找不到目标应用"
            "search" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s").buildUpon().appendQueryParameter("wd", task.target).build())
            "navigate" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1").appendQueryParameter("destination", task.target).build())
            else -> Intent(if (task.target == "wifi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS)
        }
        return try {
            if (task.action == "draft") {
                val clip = ClipData.newPlainText("消息草稿", task.message)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                }
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }
            activity.startActivity(intent)
            if (task.action == "draft") "草稿已复制，请在目标应用选人、粘贴并发送" else "已交给 Android 打开，请查看目标界面"
        } catch (_: android.content.ActivityNotFoundException) {
            "手机没有能够处理这个操作的应用"
        } catch (_: SecurityException) {
            "系统拒绝了这次操作"
        }
    }
}
