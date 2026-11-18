package com.example.diary.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.diary.data.DiaryEntry

object ReminderScheduler {
    // “稍后提醒 / 测试提醒”使用独立的 requestCode 段，
    // 不会覆盖条目本身下一次的正式提醒
    private const val SNOOZE_REQUEST_BASE = 1_000_000_000

    fun schedule(context: Context, entry: DiaryEntry) {
        if (entry.reminderType == "none" || entry.reminderTime <= 0) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("entry_id", entry.id)
            putExtra("title", entry.title)
            putExtra("content", entry.content)
            putExtra("reminder_type", entry.reminderType)
            putExtra("reminder_interval", entry.reminderInterval)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, entry.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用 setAlarmClock：系统级闹钟，被杀概率最低，状态栏显示闹钟图标
        val showIntent = Intent(context, com.example.diary.MainActivity::class.java).let {
            PendingIntent.getActivity(
                context, entry.id.toInt(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmClockInfo = AlarmManager.AlarmClockInfo(entry.reminderTime, showIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            }
        } else {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        }
    }

    /**
     * 稍后提醒（以及设置页的“10 秒后测试”）：delayMs 毫秒后再触发一次，
     * 触发后不会按重复规则顺延，只响这一次。
     */
    fun scheduleSnooze(context: Context, entryId: Long, title: String, content: String, delayMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        val requestCode = SNOOZE_REQUEST_BASE + entryId.toInt()

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("entry_id", entryId)
            putExtra("title", title)
            putExtra("content", content)
            putExtra("reminder_type", "once")
            putExtra("reminder_interval", 0)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (canScheduleExact(context)) {
            val showIntent = PendingIntent.getActivity(
                context, requestCode,
                Intent(context, com.example.diary.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), pendingIntent)
        } else {
            // 没有精确闹钟权限时退而求其次，可能有少量延迟
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context, entryId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, entryId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        // 同时取消该条目可能还挂着的“稍后提醒”
        PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_BASE + entryId.toInt(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
    }

    fun canScheduleExact(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    fun openAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            context.startActivity(intent)
        }
    }

    /** Android 14+ 才有“全屏通知”这个单独开关；更低版本默认可用 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true
        } else true
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Throwable) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }
}
