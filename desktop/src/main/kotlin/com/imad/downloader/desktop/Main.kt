package com.imad.downloader.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.imad.downloader.core.BatchUrls
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.DownloadStatus
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Second launch (or a URL passed on the command line): hand over to the running instance.
    if (IntegrationServer.forwardToRunningInstance(args)) exitProcess(0)
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        runCatching { AppDirs.logFile.appendText("${java.time.LocalDateTime.now()} ${e.stackTraceToString()}\n") }
    }
    val app = AppState()
    app.start()
    if ("--tray" in args) app.windowVisible = false
    args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let { app.showFileInfo(DownloadRequest(it)) }

    application {
        val tray = rememberTrayState()
        fun quit() {
            app.shutdown()
            exitApplication()
        }
        app.exitApp = ::quit
        app.notifier = { title, text -> if (app.settings.notifications) tray.sendNotification(Notification(title, text, Notification.Type.Info)) }

        Tray(
            icon = painterResource("icons/tray.png"),
            state = tray,
            tooltip = "Hyper Downloader",
            onAction = { app.showWindow() },
            menu = {
                Item("Open Hyper Downloader", onClick = { app.showWindow() })
                Item("Add URL...", onClick = { app.showWindow(); app.addUrlOpen = Platform.clipboardText().orEmpty() })
                Separator()
                Item(if (app.settings.speedLimitEnabled) "Turn speed limiter off" else "Turn speed limiter on", onClick = {
                    app.updateSettings(app.settings.copy(speedLimitEnabled = !app.settings.speedLimitEnabled))
                })
                CheckboxItem("Watch clipboard", app.settings.monitorClipboard) { app.updateSettings(app.settings.copy(monitorClipboard = it)) }
                Item("Stop all downloads", onClick = { app.engine.pauseAll() })
                Separator()
                Item("Exit", onClick = ::quit)
            },
        )

        val windowState = rememberWindowState(
            size = DpSize(app.settings.windowWidth.dp, app.settings.windowHeight.dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = { if (app.settings.minimizeToTray) app.windowVisible = false else quit() },
            visible = app.windowVisible,
            state = windowState,
            title = "Hyper Downloader",
            icon = painterResource("icons/app.png"),
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when {
                    e.key == Key.Delete && app.selection.isNotEmpty() -> { app.requestDelete(app.selection.toList()); true }
                    e.isCtrlPressed && e.key == Key.A -> {
                        app.selection.clear(); app.selection.addAll(app.visibleItems(app.engine.downloads).map { it.id }); true
                    }
                    e.isCtrlPressed && e.key == Key.N -> { app.addUrlOpen = Platform.clipboardText().orEmpty(); true }
                    e.isCtrlPressed && e.key == Key.V && app.addUrlOpen == null && app.fileInfo == null -> {
                        val links = BatchUrls.extractLinks(Platform.clipboardText().orEmpty())
                        when {
                            links.size == 1 -> app.showFileInfo(DownloadRequest(links[0]))
                            links.size > 1 -> app.linksDialog = CapturedLinks(links.map { CapturedLink(it) })
                        }
                        links.isNotEmpty()
                    }
                    e.key == Key.Enter && app.selection.size == 1 -> {
                        app.engine.get(app.selection.first())?.let { openItem(app, it) }; true
                    }
                    else -> false
                }
            },
        ) {
            LaunchedEffect(app.bringToFrontTick) {
                if (app.bringToFrontTick > 0) {
                    window.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            LaunchedEffect(windowState.size) {
                kotlinx.coroutines.delay(1000)
                val w = windowState.size.width.value.toInt()
                val h = windowState.size.height.value.toInt()
                if (w != app.settings.windowWidth || h != app.settings.windowHeight) app.updateSettings(app.settings.copy(windowWidth = w, windowHeight = h))
            }
            ImadTheme(app.settings.theme) {
                AppMenuBar(app, ::quit)
                MainContent(app)
            }
        }
        AllDialogs(app)
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.FrameWindowScope.AppMenuBar(app: AppState, quit: () -> Unit) {
    val sel = remember(app.selection.toList()) { app.selection.mapNotNull { app.engine.get(it) } }
    val one = sel.singleOrNull()
    MenuBar {
        Menu("Tasks", mnemonic = 'T') {
            Item("Add new download...", shortcut = KeyShortcut(Key.N, ctrl = true), onClick = { app.addUrlOpen = Platform.clipboardText().orEmpty() })
            Item("Add batch download...", onClick = { app.batchOpen = true })
            Item("Add links from clipboard", shortcut = KeyShortcut(Key.V, ctrl = true), onClick = {
                val links = BatchUrls.extractLinks(Platform.clipboardText().orEmpty())
                when {
                    links.size == 1 -> app.showFileInfo(DownloadRequest(links[0]))
                    links.size > 1 -> app.linksDialog = CapturedLinks(links.map { CapturedLink(it) })
                    else -> app.message = "The clipboard has no http or https links."
                }
            })
            Separator()
            Item("Import links from text file...", onClick = {
                Platform.chooseOpenFile("Import links")?.let { f ->
                    val links = BatchUrls.extractLinks(f.readText())
                    if (links.isEmpty()) app.message = "No links found in ${f.name}." else app.linksDialog = CapturedLinks(links.map { CapturedLink(it) })
                }
            })
            Item("Export links to text file...", onClick = {
                Platform.chooseSaveFile(System.getProperty("user.home"), "imad-downloads.txt", "Export links")?.let { f ->
                    f.writeText(app.engine.downloads.joinToString(System.lineSeparator()) { it.url })
                }
            })
            Separator()
            Item("Exit", onClick = quit)
        }
        Menu("File", mnemonic = 'F') {
            Item("Open", enabled = one?.status == DownloadStatus.COMPLETED, onClick = { one?.let { Platform.openFile(it.filePath) } })
            Item("Open with...", enabled = one?.status == DownloadStatus.COMPLETED, onClick = { one?.let { Platform.openWith(it.filePath) } })
            Item("Open folder", enabled = one != null, onClick = { one?.let { Platform.showInFolder(it.filePath) } })
            Separator()
            Item("Properties", enabled = one != null, onClick = { app.propertiesFor = one?.id })
            Item("Delete", enabled = sel.isNotEmpty(), shortcut = KeyShortcut(Key.Delete), onClick = { app.requestDelete(sel.map { it.id }) })
        }
        Menu("Downloads", mnemonic = 'D') {
            Item("Resume", enabled = sel.any { it.status.canStart }, onClick = { app.resumeSelected() })
            Item("Stop", enabled = sel.any { it.status.isActive || it.status == DownloadStatus.QUEUED }, onClick = { app.stopSelected() })
            Item("Redownload", enabled = sel.isNotEmpty(), onClick = { sel.forEach { app.engine.redownload(it.id) } })
            Separator()
            Item("Resume all", onClick = { app.engine.startAll() })
            Item("Stop all", onClick = { app.engine.pauseAll() })
            Item("Delete all completed", onClick = { app.deleteCompleted() })
            Separator()
            Item("Scheduler...", onClick = { app.schedulerOpen = true })
            Menu("Speed Limiter") {
                CheckboxItem("Turn on", app.settings.speedLimitEnabled) { app.updateSettings(app.settings.copy(speedLimitEnabled = it)) }
                Item("Settings...", onClick = { app.optionsOpen = true })
            }
            Item("Options...", onClick = { app.optionsOpen = true })
        }
        Menu("View", mnemonic = 'V') {
            RadioButtonItem("System theme", app.settings.theme == ThemeMode.SYSTEM) { app.updateSettings(app.settings.copy(theme = ThemeMode.SYSTEM)) }
            RadioButtonItem("Light theme", app.settings.theme == ThemeMode.LIGHT) { app.updateSettings(app.settings.copy(theme = ThemeMode.LIGHT)) }
            RadioButtonItem("Dark theme", app.settings.theme == ThemeMode.DARK) { app.updateSettings(app.settings.copy(theme = ThemeMode.DARK)) }
            Separator()
            Item("Open download folder", onClick = { File(app.settings.saveDir).mkdirs(); Platform.showInFolder(app.settings.saveDir) })
        }
        Menu("Help", mnemonic = 'H') {
            Item("Connect browsers...", onClick = { app.browserSetupOpen = true })
            Item("Project page", onClick = { Platform.browse("https://github.com/ImadKhan1999/Imad-Downloader") })
            Item("Check for updates...", onClick = { app.checkForUpdates() })
            Item("About Hyper Downloader", onClick = { app.aboutOpen = true })
        }
    }
}
