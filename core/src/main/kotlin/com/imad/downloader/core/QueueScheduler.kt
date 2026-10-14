package com.imad.downloader.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Starts and stops queues at their scheduled times (IDM "Scheduler", 1DM "Schedule").
 * [evaluate] is pure so it can be tested with any clock.
 */
class QueueScheduler(
    private val engine: DownloadEngine,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val startedOn = HashMap<String, LocalDate>()
    private val stoppedOn = HashMap<String, LocalDate>()

    sealed interface Action {
        val queueId: String
        data class Start(override val queueId: String) : Action
        data class Stop(override val queueId: String) : Action
    }

    /** Which queues should start or stop at [at]. Each action fires at most once per day. */
    @Synchronized
    fun evaluate(queues: List<DownloadQueue>, at: LocalDateTime): List<Action> {
        val out = mutableListOf<Action>()
        val minute = at.hour * 60 + at.minute
        val today = at.toLocalDate()
        for (q in queues) {
            val s = q.schedule ?: continue
            if (!s.enabled) continue
            if (s.days.isNotEmpty() && at.dayOfWeek.value !in s.days) continue
            val stop = s.stopMinute
            val inWindow = if (stop == null) minute >= s.startMinute
            else if (stop > s.startMinute) minute in s.startMinute until stop
            else minute >= s.startMinute || minute < stop // window crosses midnight
            if (inWindow && !q.running && startedOn[q.id] != today && minute >= s.startMinute) {
                startedOn[q.id] = today
                out += Action.Start(q.id)
            }
            if (stop != null && q.running && !inWindow && stoppedOn[q.id] != today && minute >= stop) {
                stoppedOn[q.id] = today
                out += Action.Stop(q.id)
            }
        }
        return out
    }

    fun tick() {
        for (a in evaluate(engine.queues, now())) when (a) {
            is Action.Start -> engine.startQueue(a.queueId)
            is Action.Stop -> engine.stopQueue(a.queueId)
        }
    }

    fun start(scope: CoroutineScope, intervalMillis: Long = 15_000): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }
            delay(intervalMillis)
        }
    }
}
