package com.imad.downloader.desktop

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Windows integration helpers: shell, clipboard, file pickers, startup, power. */
object Platform {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")

    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    fun openFile(path: String) {
        val f = File(path)
        if (!f.exists()) return
        runCatching { Desktop.getDesktop().open(f) }.onFailure {
            if (isWindows) ProcessBuilder("cmd", "/c", "start", "", f.absolutePath).start()
        }
    }

    /** Opens Explorer with the file selected (falls back to the folder). */
    fun showInFolder(path: String) {
        val f = File(path)
        if (isWindows && f.exists()) {
            ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
        } else {
            val dir = if (f.isDirectory) f else f.parentFile
            dir?.let { d -> d.mkdirs(); runCatching { Desktop.getDesktop().open(d) } }
        }
    }

    fun openWith(path: String) {
        if (isWindows && File(path).exists()) {
            ProcessBuilder("rundll32.exe", "shell32.dll,OpenAs_RunDLL", File(path).absolutePath).start()
        }
    }

    fun browse(url: String) {
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    fun clipboardText(): String? = runCatching {
        val cb = Toolkit.getDefaultToolkit().systemClipboard
        if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) cb.getData(DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    fun chooseFolder(initial: String?, title: String = "Choose folder"): String? {
        val chooser = JFileChooser(initial?.let(::File)).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    fun chooseSaveFile(initialDir: String, name: String, title: String = "Save as"): File? {
        val chooser = JFileChooser(File(initialDir)).apply {
            dialogTitle = title
            selectedFile = File(initialDir, name)
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun chooseOpenFile(title: String = "Open"): File? {
        val chooser = JFileChooser().apply { dialogTitle = title }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun beep() = runCatching { Toolkit.getDefaultToolkit().beep() }

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_VALUE = "iDownloader"
    private val OLD_RUN_VALUES = listOf("Hyper Downloader", "Imad Downloader") // before the renames

    /** The installed launcher (jpackage sets jpackage.app-path); null when running from Gradle. */
    private val launcherPath: String? get() = System.getProperty("jpackage.app-path")

    fun setLaunchOnStartup(enabled: Boolean): Boolean {
        if (!isWindows) return false
        val exe = launcherPath ?: return false
        OLD_RUN_VALUES.forEach { v -> runCatching { ProcessBuilder("reg", "delete", RUN_KEY, "/v", v, "/f").redirectErrorStream(true).start().waitFor() } }
        val cmd = if (enabled) {
            listOf("reg", "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f")
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", RUN_VALUE, "/f")
        }
        return runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    fun runCompletionAction(action: CompletionAction, exitApp: () -> Unit) {
        when (action) {
            CompletionAction.NOTHING -> Unit
            CompletionAction.EXIT -> exitApp()
            CompletionAction.SLEEP -> if (isWindows) ProcessBuilder("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0").start()
            CompletionAction.SHUTDOWN -> if (isWindows) {
                ProcessBuilder("shutdown", "/s", "/t", "60", "/c", "iDownloader finished the queue. Shutting down in 60 seconds.").start()
            }
        }
    }

    fun freeSpace(dir: String): Long = runCatching {
        var f: File? = File(dir)
        while (f != null && !f.exists()) f = f.parentFile
        f?.usableSpace ?: -1
    }.getOrDefault(-1)
}
