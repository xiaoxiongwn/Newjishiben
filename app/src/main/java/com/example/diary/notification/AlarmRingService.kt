package com.example.diary.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.diary.MainActivity
import com.example.diary.R

/**
 * 闹钟式提醒服务：到点后循环响铃 + 循环震动 + 点亮屏幕弹出提醒页。
 *
 * - 铃声走 USAGE_ALARM（闹钟音量），手机静音时通常仍会响
 * - 震动直接调用 Vibrator，不依赖通知渠道的震动开关
 * - 最长响 RING_TIMEOUT_MS，超时后自动停止，并留一条普通通知（不会丢提醒）
 * - 用户点“关闭 / 稍后提醒”，或滑掉通知，都会立刻停止
 */
class AlarmRingService : Service() {

    companion object {
        const val CHANNEL_ALARM = "diary_alarm_v1"
        const val CHANNEL_MISSED = "diary_missed_v1"
        const val NOTIFICATION_ID = 2001
        private const val MISSED_ID_BASE = 3000

        const val ACTION_START = "com.example.diary.action.ALARM_START"
        const val ACTION_STOP = "com.example.diary.action.ALARM_STOP"
        const val ACTION_SNOOZE = "com.example.diary.action.ALARM_SNOOZE"

        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT = "content"

        const val RING_TIMEOUT_MS = 60_000L
        const val SNOOZE_MINUTES = 10

        private val VIBRATE_PATTERN = longArrayOf(0, 800, 500, 800, 500, 1200, 1500)

        /** 开始响铃（由 ReminderReceiver 在闹钟触发时调用） */
        fun start(context: Context, entryId: Long, title: String, content: String) {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ENTRY_ID, entryId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止响铃（用户在界面上点“关闭”） */
        fun stop(context: Context) {
            context.startService(
                Intent(context, AlarmRingService::class.java).apply { action = ACTION_STOP }
            )
        }

        /** 稍后提醒：停止当前响铃，并在 SNOOZE_MINUTES 分钟后再提醒一次 */
        fun snooze(context: Context, entryId: Long, title: String, content: String) {
            context.startService(
                Intent(context, AlarmRingService::class.java).apply {
                    action = ACTION_SNOOZE
                    putExtra(EXTRA_ENTRY_ID, entryId)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_CONTENT, content)
                }
            )
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { onTimeout() }

    private var ringing = false
    private var curId = -1L
    private var curTitle = ""
    private var curContent = ""

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_SNOOZE -> {
                val id = intent.getLongExtra(EXTRA_ENTRY_ID, curId)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: curTitle
                val content = intent.getStringExtra(EXTRA_CONTENT) ?: curContent
                ReminderScheduler.scheduleSnooze(
                    this, id, title, content, SNOOZE_MINUTES * 60_000L
                )
                finishRinging()
            }
            else -> finishRinging() // ACTION_STOP 或未知
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 流程

    private fun handleStart(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "日记提醒"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""

        // 正在响另一条提醒：把上一条降级成普通通知，避免被新提醒覆盖后看不到
        if (ringing && curId != id) {
            postMissed(curId, curTitle, curContent)
        }
        curId = id
        curTitle = title
        curContent = content

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildAlarmNotification(id, title, content, ongoing = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (t: Throwable) {
            // 被系统/厂商拦截，没法作为前台服务运行：退化成一条带全屏 Intent 的普通通知
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildAlarmNotification(id, title, content, ongoing = false))
            } catch (_: Throwable) {
            }
            stopSelf()
            return
        }
        startRinging()
    }

    private fun finishRinging() {
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onTimeout() {
        val id = curId
        val title = curTitle
        val content = curContent
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postMissed(id, title, content)
        stopSelf()
    }

    // ---------------------------------------------------------------- 响铃 / 震动

    private fun startRinging() {
        stopRinging() // 清掉上一次可能残留的状态
        ringing = true
        wakeScreen()
        acquireWakeLock()
        startSound()
        startVibration()
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
    }

    private fun stopRinging() {
        ringing = false
        handler.removeCallbacks(timeoutRunnable)

        val p = player
        player = null
        if (p != null) {
            try {
                if (p.isPlaying) p.stop()
            } catch (_: Throwable) {
            }
            try {
                p.release()
            } catch (_: Throwable) {
            }
        }

        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null

        abandonFocus()

        val wl = wakeLock
        wakeLock = null
        if (wl != null) {
            try {
                if (wl.isHeld) wl.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun startSound() {
        val attrs = alarmAudioAttributes()
        requestFocus(attrs)
        var mp: MediaPlayer? = null
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val created = MediaPlayer()
            mp = created
            created.setAudioAttributes(attrs)
            created.setDataSource(this, uri)
            created.isLooping = true
            created.prepare()
            created.start()
            player = created
        } catch (t: Throwable) {
            try {
                mp?.release()
            } catch (_: Throwable) {
            }
            player = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(VIBRATE_PATTERN, 0) // 0 = 从头无限重复
            // 带上 USAGE_ALARM，避免系统按“触感反馈”开关把震动吞掉
            v.vibrate(effect, alarmAudioAttributes())
            vibrator = v
        } catch (_: Throwable) {
        }
    }

    private fun requestFocus(attrs: AudioAttributes) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Throwable) {
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(req)
        } catch (_: Throwable) {
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diary:alarm")
            wl.acquire(RING_TIMEOUT_MS + 5_000L)
            wakeLock = wl
        } catch (_: Throwable) {
        }
    }

    /**
     * 兜底点亮屏幕 10 秒：即使“全屏通知”权限没开，息屏时屏幕也会亮起来，
     * 用户至少能看到锁屏上的提醒通知。到时间自动释放。
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "diary:alarm_screen"
            )
            wl.acquire(10_000L)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // 声音和震动都由服务自己控制，渠道里关掉，避免重复响
        val alarm = NotificationChannel(CHANNEL_ALARM, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点响铃、震动并亮屏提醒"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(alarm)

        val missed = NotificationChannel(CHANNEL_MISSED, "未处理的提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "响铃结束后保留的提醒记录"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(missed)
    }

    private fun alarmActivityIntent(id: Long, title: String, content: String): PendingIntent {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ENTRY_ID, id)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_CONTENT, content)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceActionIntent(
        action: String, requestCode: Int, id: Long, title: String, content: String
    ): PendingIntent {
        val intent = Intent(this, AlarmRingService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ENTRY_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_CONTENT, content)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildAlarmNotification(
        id: Long, title: String, content: String, ongoing: Boolean
    ): Notification {
        val accent = ContextCompat.getColor(this, R.color.notification_accent)
        val shownContent = content.ifBlank { "提醒时间到了" }
        val open = alarmActivityIntent(id, title, content)
        val stopPi = serviceActionIntent(ACTION_STOP, 1, id, title, content)
        val snoozePi = serviceActionIntent(ACTION_SNOOZE, 2, id, title, content)

        return NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(shownContent.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText(shownContent))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true) // 息屏/锁屏时直接弹出 AlarmActivity
            .setDeleteIntent(stopPi)         // 滑掉通知 = 停止响铃
            .addAction(0, "关闭", stopPi)
            .addAction(0, "稍后${SNOOZE_MINUTES}分钟", snoozePi)
            .build()
    }

    /** 响铃结束（超时 / 被新提醒顶替）后留下的普通通知：静音、可点开、可滑掉 */
    private fun postMissed(id: Long, title: String, content: String) {
        try {
            val accent = ContextCompat.getColor(this, R.color.notification_accent)
            val notifyId = MISSED_ID_BASE + id.toInt()
            val open = PendingIntent.getActivity(
                this, notifyId,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("entry_id", id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val shownContent = content.ifBlank { "提醒时间到了" }
            val n = NotificationCompat.Builder(this, CHANNEL_MISSED)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setColor(accent)
                .setContentTitle("⏰ $title")
                .setContentText(shownContent.take(50))
                .setStyle(NotificationCompat.BigTextStyle().bigText(shownContent))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            getSystemService(NotificationManager::class.java).notify(notifyId, n)
        } catch (_: Throwable) {
        }
    }
}
