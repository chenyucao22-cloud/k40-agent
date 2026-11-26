package dev.channing.androidagent

import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.RemoteViews

object AssistantEntrypoints {
    private const val CHANNEL = "local_assistant_activity"
    private const val NOTICE = 41
    var stopWork: (() -> Unit)? = null
    private var label = "助手"

    fun open(context: Context): PendingIntent = PendingIntent.getActivity(context, 22,
        Intent(context, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun stop(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 23,
        Intent(context, StopAssistantReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun status(context: Context, text: String?) {
        label = text ?: "助手"
        val app = context.applicationContext
        val widgets = AppWidgetManager.getInstance(app)
        listOf(SmallWidget::class.java, VoiceWidget::class.java, FullWidget::class.java).forEach { type ->
            val ids = widgets.getAppWidgetIds(ComponentName(app, type))
            if (ids.isNotEmpty()) widgets.updateAppWidget(ids, view(app, type == SmallWidget::class.java))
        }
        val notifications = app.getSystemService(NotificationManager::class.java)
        if (text == null) { notifications.cancel(NOTICE); return }
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "本地助手任务", NotificationManager.IMPORTANCE_LOW))
        if (!notifications.areNotificationsEnabled()) return
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Android Agent")
            .setContentText(text)
            .setContentIntent(open(app))
            .addAction(Notification.Action.Builder(null, "停止", stop(app)).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { notifications.notify(NOTICE, notification) }
    }

    fun view(context: Context, small: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.assistant_widget).apply {
            setTextViewText(R.id.widget_open, label)
            setViewVisibility(R.id.widget_stop, if (small && stopWork == null) View.GONE else View.VISIBLE)
            setOnClickPendingIntent(R.id.widget_open, open(context))
            setOnClickPendingIntent(R.id.widget_stop, stop(context))
        }
}

class StopAssistantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistantEntrypoints.stopWork?.invoke()
        BridgeEpoch.revoke()
        AgentService.instance?.emergencyStop()
    }
}
open class AssistantWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, ids: IntArray) {
        appWidgetManager.updateAppWidget(ids, AssistantEntrypoints.view(context, this is SmallWidget))
    }
}
class SmallWidget : AssistantWidget()
class VoiceWidget : AssistantWidget()
class FullWidget : AssistantWidget()

class AssistantTile : TileService() {
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is used only on Android 13 and earlier.
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(AssistantEntrypoints.open(this))
        else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
class DeviceAssistantService : VoiceInteractionService()
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onCreateContentView(): View = Button(context).apply {
            text = "打开 Android Agent 语音窗口"
            setOnClickListener {
                startAssistantActivity(Intent(context, QuickActivity::class.java))
                finish()
            }
        }
    }
}
