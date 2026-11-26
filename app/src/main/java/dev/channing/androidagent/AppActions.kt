package dev.channing.androidagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ComponentName
import android.provider.AlarmClock

/** Only called by the foreground confirmation dialog in BridgeActivity. */
object AppActions {
    data class Outcome(val accepted: Boolean, val message: String)

    data class Launchable(val name: String, val component: ComponentName)

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    fun launchableApps(activity: Activity): List<Launchable> {
        val manager = activity.packageManager
        return manager.queryIntentActivities(launcherIntent(), 0)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != activity.packageName }
            .map {
                val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                val label = it.loadLabel(manager).toString()
                Launchable("$label · ${component.packageName}", component)
            }
            .distinctBy(Launchable::component)
            .sortedBy(Launchable::name)
    }

    fun execute(activity: Activity, action: Action, app: Launchable? = null): Outcome {
        val intent = when (action) {
            Action.OPEN_APPS -> launcherIntent().setComponent(requireNotNull(app).component)
            Action.OPEN_QQ -> activity.packageManager.getLaunchIntentForPackage(requireNotNull(action.target))
            Action.TIMER_5_MIN -> Intent(AlarmClock.ACTION_SET_TIMER)
                .setPackage(requireNotNull(action.target))
                .putExtra(AlarmClock.EXTRA_LENGTH, 300)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            else -> error("Expected an Android app action")
        }
        val unavailable = Outcome(false, "找不到可处理此任务的应用，请检查 QQ 或系统时钟。")
        if (intent == null) return unavailable
        return try {
            activity.startActivity(intent)
            val message = when (action) {
                Action.OPEN_APPS -> "已向 Android 提交打开 ${requireNotNull(app).name} 的请求。"
                Action.OPEN_QQ -> "已向 Android 提交打开 QQ 的请求。"
                Action.TIMER_5_MIN -> "已把 5 分钟倒计时交给系统时钟，请检查时钟界面。"
                else -> error("Expected an Android app action")
            }
            Outcome(true, message)
        } catch (_: ActivityNotFoundException) {
            unavailable
        } catch (_: SecurityException) {
            Outcome(false, "Android 拒绝了这次操作，请检查系统设置。")
        }
    }
}
