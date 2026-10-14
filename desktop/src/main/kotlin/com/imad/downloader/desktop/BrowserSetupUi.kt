package com.imad.downloader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date

/** Last time this browser's extension talked to the app (Chrome and Brave look the same). */
fun AppState.lastSeen(b: InstalledBrowser): Long? = settings.connectedBrowsers[b.id]
    ?: if (b.id == "brave") settings.connectedBrowsers["chrome"] else null

/** List of the browsers on this PC with their connection state and an "Add" button each. */
@Composable
fun BrowserConnectPanel(app: AppState) {
    var guide by remember { mutableStateOf<InstalledBrowser?>(null) }
    val browsers = app.browsers
    val p = LocalPalette.current
    if (browsers.isEmpty()) {
        Hint("No supported browser was found (Chrome, Edge, Brave, Opera, Vivaldi, Firefox).")
        return
    }
    browsers.forEach { b ->
        val seen = app.lastSeen(b)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                .border(1.dp, p.gridLine, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (seen != null) Icons.Filled.CheckCircle else Icons.Filled.Public, null, Modifier.size(22.dp),
                tint = if (seen != null) p.complete else p.muted)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(b.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (seen != null) "Connected (last seen ${SimpleDateFormat("MMM d, HH:mm").format(Date(seen))})" else "Extension not added yet",
                    fontSize = 12.sp, color = if (seen != null) p.complete else p.muted,
                )
            }
            if (guide?.id == b.id) TextButton({ guide = null }) { Text("Hide steps") }
            else if (seen == null) Button({ guide = b; startInstall(app, b) }) { Text("Add extension") }
            else OutlinedButton({ guide = b; startInstall(app, b) }) { Text("Add again") }
        }
        if (guide?.id == b.id) InstallSteps(app, b)
    }
}

private fun startInstall(app: AppState, b: InstalledBrowser) {
    val store = ExtensionStores.storePage(b)
    if (store != null) {
        BrowserSetup.registerFromStore(b)
        BrowserSetup.open(b, store)
        return
    }
    val folder = BrowserSetup.stableCopy(b.family) ?: run { app.message = "The extension files are missing from this installation."; return }
    Platform.copy(if (b.family == BrowserFamily.FIREFOX) java.io.File(folder, "manifest.json").path else folder.path)
    BrowserSetup.open(b, b.extensionsPage)
}

@Composable
private fun InstallSteps(app: AppState, b: InstalledBrowser) {
    val p = LocalPalette.current
    val folder = java.io.File(AppDirs.data, "browser-extension/" + if (b.family == BrowserFamily.FIREFOX) "firefox" else "chromium")
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)).padding(12.dp),
    ) {
        if (ExtensionStores.storePage(b) != null) {
            Text("${b.name} opened the extension's store page. Click \"Add\" there; the browser may also show \"New extension added\" - choose \"Enable\".", fontSize = 13.sp)
            return@Column
        }
        if (b.family == BrowserFamily.FIREFOX) {
            Step(1, "Firefox opened its \"This Firefox\" page (about:debugging). If not: type about:debugging in the address bar.")
            Step(2, "Click \"Load Temporary Add-on...\" and pick manifest.json in the folder below (the path is already copied - paste it into the file box).")
            Step(3, "This row turns green when Firefox talks to Imad Downloader.")
            Hint("Firefox removes add-ons loaded this way when it restarts. A permanent Firefox install comes with the Firefox Add-ons listing.")
        } else {
            Step(1, "${b.name} opened its extensions page (${b.extensionsPage}). If not: type it in the address bar.")
            Step(2, "Turn on \"Developer mode\" (top right, or bottom left in Edge).")
            Step(3, "Click \"Load unpacked\" and choose the folder below (the path is already copied - paste it into the folder box).")
            Step(4, "This row turns green when ${b.name} talks to Imad Downloader. It keeps working after app updates.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Field(if (b.family == BrowserFamily.FIREFOX) java.io.File(folder, "manifest.json").path else folder.path, {}, Modifier.weight(1f), readOnly = true)
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ Platform.copy(if (b.family == BrowserFamily.FIREFOX) java.io.File(folder, "manifest.json").path else folder.path) }) { Text("Copy") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ Platform.showInFolder(folder.path) }) { Text("Show") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ BrowserSetup.open(b, b.extensionsPage) }) { Text("Open ${b.name.substringAfterLast(' ')}") }
        }
        if (app.lastSeen(b) == null) Text("Waiting for ${b.name}...", fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$n.", Modifier.width(18.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(text, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** First-run "Connect your browsers" dialog. */
@Composable
fun BrowserSetupDialog(app: AppState) {
    if (!app.browserSetupOpen) return
    AppDialog("Connect your browsers", 640.dp, 520.dp, app.settings.theme, { app.browserSetupOpen = false }) {
        Text("Let Imad Downloader take over downloads in your browsers", style = MaterialTheme.typography.titleMedium)
        Hint("Add the Imad Downloader extension to each browser you use. Downloads, videos and the right-click \"Download with Imad Downloader\" then come here.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)) { BrowserConnectPanel(app) }
        ButtonRow {
            TextButton({ app.browserSetupOpen = false }) { Text("Later") }
            Button({ app.browserSetupOpen = false }) { Text("Done") }
        }
    }
}
