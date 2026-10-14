@file:OptIn(ExperimentalMaterial3Api::class)

package com.imad.downloader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.QueueAlarms
import com.imad.downloader.core.DownloadEngine
import com.imad.downloader.core.DownloadQueue
import com.imad.downloader.core.DownloadStatus
import com.imad.downloader.core.EngineState
import com.imad.downloader.core.QueueSchedule

private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

fun scheduleSummary(s: QueueSchedule?): String {
    if (s == null || !s.enabled) return "No schedule"
    val days = when {
        s.days.isEmpty() || s.days.size == 7 -> "Every day"
        s.days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        s.days == setOf(6, 7) -> "Weekends"
        else -> s.days.sorted().joinToString(", ") { dayNames[it - 1] }
    }
    return "$days, ${time(s.startMinute)}" + (s.stopMinute?.let { " to ${time(it)}" } ?: "")
}

@Composable
fun QueuesScreen(engine: DownloadEngine, onBack: () -> Unit, canStart: () -> Boolean) {
    val state by engine.state.collectAsState()
    var editing by remember { mutableStateOf<DownloadQueue?>(null) }
    var creating by remember { mutableStateOf(false) }
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text("Queues and schedule", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New queue") }, modifier = Modifier.testTag("new-queue"))
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Put downloads in a queue with \"Later\" or from a download's details. A queue runs its files one after another, " +
                        "by hand or at the scheduled time, even when the app is closed.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
                )
                if (!QueueAlarms.canExact(context) && state.queues.any { it.schedule?.enabled == true } && Build.VERSION.SDK_INT >= 31) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Allow exact alarms so schedules start on time. Without it Android may start them a few minutes late.", fontSize = 13.sp)
                            TextButton({
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            }) { Text("Allow") }
                        }
                    }
                }
            }
            items(state.queues, key = { it.id }) { q ->
                val files = state.downloads.filter { it.queueId == q.id }
                val left = files.count { it.status != DownloadStatus.COMPLETED }
                Card(Modifier.fillMaxWidth().testTag("queue-${q.name}"), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Queue, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(q.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("${files.size} files, $left left  •  ${q.maxConcurrent} at a time", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (q.running) Text("Running", color = Success, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(scheduleSummary(q.schedule), fontSize = 13.sp)
                        }
                        files.take(3).forEach {
                            Text("•  ${it.fileName}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (q.running) OutlinedButton({ engine.stopQueue(q.id) }) { Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop") }
                            else FilledTonalButton({ if (canStart()) engine.startQueue(q.id) }, enabled = left > 0, modifier = Modifier.testTag("start-${q.name}")) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Start now")
                            }
                            OutlinedButton({ editing = q }, modifier = Modifier.testTag("edit-${q.name}")) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit") }
                            if (q.id != EngineState.MAIN_QUEUE_ID) IconButton({ engine.removeQueue(q.id) }) { Icon(Icons.Filled.Delete, "Delete queue") }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New queue") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("queue-name")) },
            confirmButton = { TextButton({ editing = engine.addQueue(name); creating = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton({ creating = false }) { Text("Cancel") } },
        )
    }
    editing?.let { q -> QueueEditor(q, onDismiss = { editing = null }) { engine.updateQueue(it); editing = null } }
}

@Composable
private fun QueueEditor(queue: DownloadQueue, onDismiss: () -> Unit, onSave: (DownloadQueue) -> Unit) {
    var name by remember { mutableStateOf(queue.name) }
    var atOnce by remember { mutableStateOf(queue.maxConcurrent) }
    val s = queue.schedule
    var scheduled by remember { mutableStateOf(s?.enabled == true) }
    var start by remember { mutableStateOf(s?.startMinute ?: 23 * 60) }
    var stop by remember { mutableStateOf(s?.stopMinute) }
    var days by remember { mutableStateOf(s?.days?.ifEmpty { (1..7).toSet() } ?: (1..7).toSet()) }
    var picking by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit queue") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                Spacer(Modifier.height(10.dp))
                Text("Files at the same time")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { n -> FilterChip(atOnce == n, { atOnce = n }, label = { Text("$n") }) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start on a schedule", Modifier.weight(1f))
                    Switch(scheduled, { scheduled = it }, modifier = Modifier.testTag("schedule-switch"))
                }
                if (scheduled) {
                    Row(Modifier.fillMaxWidth().clickable { picking = "start" }.padding(vertical = 8.dp)) {
                        Text("Start at", Modifier.weight(1f)); Text(time(start), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Stop at", Modifier.weight(1f))
                        if (stop != null) {
                            Text(time(stop!!), Modifier.clickable { picking = "stop" }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            TextButton({ stop = null }) { Text("None") }
                        } else TextButton({ stop = 7 * 60; picking = "stop" }) { Text("Add") }
                    }
                    Text("Days")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        dayNames.forEachIndexed { i, d ->
                            val n = i + 1
                            FilterChip(n in days, { days = if (n in days) days - n else days + n }, label = { Text(d) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                val sched = if (scheduled || s != null) QueueSchedule(start, stop, if (days.size == 7) emptySet() else days, scheduled) else null
                onSave(queue.copy(name = name.trim().ifBlank { queue.name }, maxConcurrent = atOnce, schedule = sched))
            }, enabled = !scheduled || days.isNotEmpty(), modifier = Modifier.testTag("save-queue")) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )

    picking?.let { which ->
        val initial = if (which == "start") start else stop ?: 0
        val tp = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            text = { TimePicker(tp) },
            confirmButton = {
                TextButton({
                    val m = tp.hour * 60 + tp.minute
                    if (which == "start") start = m else stop = m
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ picking = null }) { Text("Cancel") } },
        )
    }
}
