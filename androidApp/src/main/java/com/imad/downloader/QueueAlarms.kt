package com.imad.downloader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.imad.downloader.core.DownloadQueue
import com.imad.downloader.core.QueueSchedule
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Wakes the app for scheduled queues even when it is closed. Uses exact alarms when the user
 * allowed them (Android 12+ setting), otherwise inexact ones that may fire a few minutes late.
 */
object QueueAlarms {
    private const val ACTION = "com.imad.downloader.QUEUE_ALARM"
    private const val EXTRA_QUEUE = "queue"
    private const val EXTRA_START = "start"

    /** Next moment at or after [from] that falls on [minuteOfDay] on an allowed weekday. */
    fun next(from: LocalDateTime, minuteOfDay: Int, days: Set<Int>): LocalDateTime {
        var t = from.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!t.isAfter(from)) t = t.plusDays(1)
        repeat(8) {
            if (days.isEmpty() || t.dayOfWeek.value in days) return t
            t = t.plusDays(1)
        }
        return t
    }

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun reschedule(context: Context, queues: List<DownloadQueue>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        queues.forEach { q ->
            val s: QueueSchedule? = q.schedule
            for (start in listOf(true, false)) {
                val pi = pending(context, q.id, start)
                am.cancel(pi)
                if (s == null || !s.enabled) continue
                val minute = if (start) s.startMinute else s.stopMinute ?: continue
                val at = next(now, minute, s.days).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    private fun pending(context: Context, queueId: String, start: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, queueId.hashCode() * 2 + if (start) 1 else 0,
            Intent(context, QueueAlarmReceiver::class.java).setAction(ACTION).putExtra(EXTRA_QUEUE, queueId).putExtra(EXTRA_START, start),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal fun handle(context: Context, intent: Intent) {
        val app = context.applicationContext as ImadApp
        val id = intent.getStringExtra(EXTRA_QUEUE)
        if (intent.action == ACTION && id != null) {
            if (intent.getBooleanExtra(EXTRA_START, true)) {
                if (app.canDownloadNow()) app.engine.startQueue(id)
            } else {
                app.engine.stopQueue(id)
            }
        }
        reschedule(context, app.engine.queues)
    }
}

/** Fires for scheduled queues and after a reboot (to set the alarms again). */
class QueueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        QueueAlarms.handle(context, intent)
    }
}
