package com.imad.downloader.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket shared by every connection that should obey one limit.
 * A rate of 0 means unlimited. The rate can be changed while downloads run.
 */
class SpeedLimiter(bytesPerSecond: Long = 0, private val clock: () -> Long = System::nanoTime) {
    @Volatile
    var bytesPerSecond: Long = bytesPerSecond
        set(value) { field = value.coerceAtLeast(0) }

    private val mutex = Mutex()
    private var tokens = 0.0
    private var last = clock()

    /** Suspends until [bytes] may be transferred under the current limit. */
    suspend fun acquire(bytes: Int) {
        if (bytes <= 0) return
        var rate = bytesPerSecond
        if (rate <= 0) return
        var wait: Long
        mutex.withLock {
            rate = bytesPerSecond
            if (rate <= 0) return
            val now = clock()
            tokens = (tokens + (now - last) / 1e9 * rate).coerceAtMost(rate.toDouble()) // at most 1s burst
            last = now
            tokens -= bytes
            wait = if (tokens >= 0) 0 else ((-tokens) / rate * 1000).toLong()
        }
        if (wait > 0) delay(wait)
    }
}

/** Moving-window speed estimate (bytes/s) and ETA, like the numbers IDM shows. */
class SpeedMeter(private val windowMillis: Long = 5_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // time ms -> total bytes

    @Synchronized
    fun sample(nowMillis: Long, totalBytes: Long) {
        samples.addLast(nowMillis to totalBytes)
        while (samples.size > 2 && nowMillis - samples.first().first > windowMillis) samples.removeFirst()
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val dt = t1 - t0
        if (dt <= 0) return 0
        return ((b1 - b0).coerceAtLeast(0) * 1000 / dt)
    }

    fun etaSeconds(remaining: Long): Long {
        val bps = bytesPerSecond()
        if (remaining < 0 || bps <= 0) return -1
        return (remaining + bps - 1) / bps
    }

    @Synchronized
    fun reset() = samples.clear()
}

/** Human-readable numbers for the UIs. */
object Format {
    fun bytes(value: Long): String {
        if (value < 0) return "Unknown"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.2f %s".format(v, units[i])
    }

    fun speed(bps: Long): String = if (bps <= 0) "" else bytes(bps) + "/s"

    fun duration(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 99 -> "> 99 h"
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min %02d sec".format(m, s)
            else -> "$s sec"
        }
    }
}
