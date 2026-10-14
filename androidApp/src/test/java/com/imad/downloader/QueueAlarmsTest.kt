package com.imad.downloader

import com.imad.downloader.core.QueueSchedule
import com.imad.downloader.ui.scheduleSummary
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueAlarmsTest {
    private val wednesday10am = LocalDateTime.of(2026, 10, 14, 10, 0)

    @Test fun `next start is later today or tomorrow`() {
        assertEquals(LocalDateTime.of(2026, 10, 14, 23, 0), QueueAlarms.next(wednesday10am, 23 * 60, emptySet()))
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), QueueAlarms.next(wednesday10am, 9 * 60, emptySet()))
        assertEquals(LocalDateTime.of(2026, 10, 15, 10, 0), QueueAlarms.next(wednesday10am, 10 * 60, emptySet()), "same minute counts as passed")
    }

    @Test fun `next start skips days that are not chosen`() {
        assertEquals(LocalDateTime.of(2026, 10, 17, 2, 0), QueueAlarms.next(wednesday10am, 2 * 60, setOf(6, 7))) // Saturday
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 0), QueueAlarms.next(wednesday10am, 8 * 60, setOf(1))) // Monday
    }

    @Test fun `schedule summary reads naturally`() {
        assertEquals("No schedule", scheduleSummary(null))
        assertEquals("Every day, 23:00 to 07:00", scheduleSummary(QueueSchedule(23 * 60, 7 * 60)))
        assertEquals("Weekdays, 01:30", scheduleSummary(QueueSchedule(90, null, setOf(1, 2, 3, 4, 5))))
        assertEquals("Weekends, 09:00", scheduleSummary(QueueSchedule(540, null, setOf(6, 7))))
        assertEquals("No schedule", scheduleSummary(QueueSchedule(540, enabled = false)))
    }
}
