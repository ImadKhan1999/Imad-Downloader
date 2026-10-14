@file:OptIn(ExperimentalMaterial3Api::class)

package com.imad.downloader.ui

import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.imad.downloader.FileActions
import com.imad.downloader.R
import com.imad.downloader.browser.BrowserController
import com.imad.downloader.browser.BrowserData
import com.imad.downloader.browser.BrowserTab
import com.imad.downloader.browser.DetectedMedia
import com.imad.downloader.browser.UrlInput
import com.imad.downloader.core.DownloadRequest
import com.imad.downloader.core.FileNames

private enum class BrowserSheet { NONE, TABS, MEDIA, BOOKMARKS, HISTORY }

@Composable
fun BrowserScreen(controller: BrowserController, data: BrowserData, onDownload: (DownloadRequest) -> Unit, active: Boolean) {
    val tab = controller.current ?: return
    var editing by remember { mutableStateOf(false) }
    var input by remember(tab.id, tab.url, editing) { mutableStateOf(if (editing) tab.url else "") }
    var sheet by remember { mutableStateOf(BrowserSheet.NONE) }
    var menu by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val store by data.state.collectAsState()

    BackHandler(enabled = active && controller.fullscreenView != null) { controller.exitFullscreen() }
    BackHandler(enabled = active && controller.fullscreenView == null && (tab.canGoBack || !tab.isStartPage)) { controller.back() }

    // Downloads the page itself started (DownloadListener).
    androidx.compose.runtime.LaunchedEffect(controller.pendingDownload) {
        controller.pendingDownload?.let { req -> controller.pendingDownload = null; onDownload(req) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // ---------------------------------------------------------------- address bar
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (tab.incognito) Color(0xFF3C3F44) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            tab.incognito -> Icons.Filled.VisibilityOff
                            tab.url.startsWith("https") -> Icons.Filled.Lock
                            else -> Icons.Filled.Search
                        },
                        null, Modifier.size(18.dp),
                        tint = if (tab.incognito) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val shown = if (editing) input else if (tab.isStartPage) "" else UrlInput.display(tab.url)
                    androidx.compose.foundation.text.BasicTextField(
                        value = shown,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (tab.incognito) Color.White else MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            controller.navigate(input)
                            editing = false
                            focus.clearFocus()
                        }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (shown.isEmpty()) Text("Search or type web address", fontSize = 15.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp).testTag("address-bar").onFocusChanged { f ->
                            if (f.isFocused && !editing) { editing = true; input = if (tab.isStartPage) "" else tab.url }
                            if (!f.isFocused) editing = false
                        },
                    )
                    if (editing && input.isNotEmpty()) {
                        IconButton({ input = "" }) { Icon(Icons.Filled.Close, "Clear", Modifier.size(18.dp)) }
                    } else if (!tab.isStartPage) {
                        IconButton({ if (tab.progress < 100) controller.stop() else controller.reload() }) {
                            Icon(if (tab.progress < 100) Icons.Filled.Close else Icons.Filled.Refresh, "Reload", Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                TabCountButton(controller.tabs.size) { sheet = BrowserSheet.TABS }
                Box {
                    IconButton({ menu = true }, Modifier.testTag("browser-menu")) { Icon(Icons.Filled.MoreVert, "Menu") }
                    BrowserMenu(menu, { menu = false }, controller, tab, data,
                        onSheet = { sheet = it }, onFind = { findText = "" },
                        onShare = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), "Share page"))
                        })
                }
            }
            if (tab.progress in 1..99 && !tab.isStartPage) {
                LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
            }
            findText?.let { ft ->
                FindBar(ft, onChange = { findText = it; controller.find(it) }, onNext = { controller.findNext(it) }, onClose = { findText = null; controller.clearFind() })
            }

            // ---------------------------------------------------------------- page
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab.isStartPage) {
                    StartPage(store.bookmarks, onOpen = { controller.navigate(it) }, onRemove = { data.removeBookmark(it) })
                }
                key(tab.id) {
                    AndroidView(
                        factory = { ctx ->
                            controller.webViewFor(tab, ctx).also { wv -> (wv.parent as? ViewGroup)?.removeView(wv) }
                        },
                        modifier = if (tab.isStartPage) Modifier.size(0.dp) else Modifier.fillMaxSize(),
                    )
                }
            }

            // ---------------------------------------------------------------- bottom bar
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({ controller.back() }, enabled = tab.canGoBack || !tab.isStartPage) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton({ controller.forward() }, enabled = tab.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                IconButton({ controller.goHome() }) { Icon(Icons.Filled.Home, "Home") }
                IconButton({ sheet = BrowserSheet.BOOKMARKS }) { Icon(Icons.Filled.Bookmark, "Bookmarks") }
                IconButton({ sheet = BrowserSheet.HISTORY }) { Icon(Icons.Filled.History, "History") }
            }
        }

        // Floating "videos found" button (1DM style).
        val videoSite = com.imad.downloader.core.MediaChoice.isKnownSite(tab.url)
        if ((tab.media.isNotEmpty() || videoSite) && !tab.isStartPage) {
            BadgedBox(
                badge = { if (!videoSite) Badge(containerColor = Color(0xFF00C853)) { Text(tab.media.size.toString()) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 72.dp),
            ) {
                FloatingActionButton({ if (videoSite) onDownload(controller.requestFor(tab.url)) else sheet = BrowserSheet.MEDIA }, containerColor = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("media-fab")) {
                    Icon(Icons.Filled.Download, "Media found", tint = Color.White)
                }
            }
        }

        controller.fullscreenView?.let { v ->
            AndroidView(factory = { (v.parent as? ViewGroup)?.removeView(v); v }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }

    controller.linkMenu?.let { lm ->
        LinkMenuSheet(lm.url, lm.imageUrl, onDismiss = { controller.linkMenu = null },
            onNewTab = { controller.newTab(lm.url, tab.incognito); controller.linkMenu = null },
            onBackgroundTab = { controller.newTab(lm.url, tab.incognito, select = false); controller.linkMenu = null },
            onDownload = { u -> onDownload(controller.requestFor(u)); controller.linkMenu = null },
            onCopy = { FileActions.copy(context, "Link", lm.url); controller.linkMenu = null })
    }

    when (sheet) {
        BrowserSheet.TABS -> TabsSheet(controller, onDismiss = { sheet = BrowserSheet.NONE })
        BrowserSheet.MEDIA -> MediaSheet(tab, onDismiss = { sheet = BrowserSheet.NONE }) { m ->
            sheet = BrowserSheet.NONE
            val base = tab.title.replace(Regex("""[\\/:*?"<>|]+"""), " ").trim().take(100)
            val ext = FileNames.fromUrl(m.url)?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 } ?: if (m.kind == DetectedMedia.Kind.AUDIO) "mp3" else "mp4"
            onDownload(controller.requestFor(m.url, if (base.isNotBlank()) "$base.$ext" else null))
        }
        BrowserSheet.BOOKMARKS -> LinkListSheet("Bookmarks", store.bookmarks.map { it.title to it.url }, { sheet = BrowserSheet.NONE }) { controller.navigate(it); sheet = BrowserSheet.NONE }
        BrowserSheet.HISTORY -> LinkListSheet("History", store.history.map { it.title.ifBlank { it.url } to it.url }, { sheet = BrowserSheet.NONE },
            action = "Clear history" to { data.clearHistory() }) { controller.navigate(it); sheet = BrowserSheet.NONE }
        BrowserSheet.NONE -> Unit
    }
}

@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).testTag("tabs-button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(if (count > 99) ":D" else count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BrowserMenu(
    open: Boolean, onDismiss: () -> Unit, controller: BrowserController, tab: BrowserTab, data: BrowserData,
    onSheet: (BrowserSheet) -> Unit, onFind: () -> Unit, onShare: () -> Unit,
) {
    DropdownMenu(open, onDismiss) {
        fun item(text: String, icon: ImageVector, action: () -> Unit) =
            @Composable { DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = { action(); onDismiss() }) }
        item("New tab", Icons.Filled.Add) { controller.newTab() }()
        item("New incognito tab", Icons.Filled.VisibilityOff) { controller.newTab(incognito = true) }()
        HorizontalDivider()
        if (!tab.isStartPage) {
            val marked = data.isBookmarked(tab.url)
            item(if (marked) "Remove bookmark" else "Add bookmark", if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder) { data.toggleBookmark(tab.url, tab.title) }()
            item("Find in page", Icons.Filled.Search, onFind)()
            item("Share page", Icons.Filled.Share, onShare)()
            item("Download this page's media", Icons.Filled.Download) { onSheet(BrowserSheet.MEDIA) }()
        }
        DropdownMenuItem(
            text = { Text("Desktop site") },
            leadingIcon = { Icon(Icons.Filled.Computer, null) },
            trailingIcon = { androidx.compose.material3.Checkbox(tab.desktopMode, null) },
            onClick = { controller.toggleDesktopMode(); onDismiss() },
        )
        item("Bookmarks", Icons.Filled.Bookmark) { onSheet(BrowserSheet.BOOKMARKS) }()
        item("History", Icons.Filled.History) { onSheet(BrowserSheet.HISTORY) }()
        if (controller.blockedAds > 0) {
            DropdownMenuItem(text = { Text("${controller.blockedAds} ads blocked", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onDismiss, enabled = false)
        }
    }
}

@Composable
private fun FindBar(text: String, onChange: (String) -> Unit, onNext: (Boolean) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(text, onChange, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in page") })
        IconButton({ onNext(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous") }
        IconButton({ onNext(true) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next") }
        IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
    }
}

@Composable
private fun StartPage(bookmarks: List<com.imad.downloader.browser.Bookmark>, onOpen: (String) -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp))
        Image(painterResource(R.drawable.logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Spacer(Modifier.height(10.dp))
        Text("Imad Browser", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Videos and files you open here can be downloaded in one tap.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bookmarks, key = { it.url }) { b ->
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(b.url) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val letter = b.title.firstOrNull()?.uppercaseChar() ?: '?'
                    val hue = (b.url.hashCode() and 0xFFFFFF) % 360
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(Color.hsv(hue.toFloat(), 0.55f, 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(letter.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
                    Spacer(Modifier.height(4.dp))
                    Text(b.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Remove") }, onClick = { onRemove(b.url); menu = false }) }
                }
            }
        }
    }
}

@Composable
private fun TabsSheet(controller: BrowserController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${controller.tabs.size} tabs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ controller.closeAll(); onDismiss() }) { Text("Close all") }
                TextButton({ controller.newTab(); onDismiss() }) { Icon(Icons.Filled.Add, null); Text("New tab") }
            }
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(controller.tabs.toList(), key = { it.id }) { t ->
                    Card(
                        Modifier.fillMaxWidth().clickable { controller.select(t); onDismiss() },
                        colors = CardDefaults.cardColors(
                            containerColor = if (t == controller.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val fav = t.favicon
                            if (fav != null) Image(fav.asImageBitmap(), null, Modifier.size(20.dp))
                            else Icon(if (t.incognito) Icons.Filled.VisibilityOff else Icons.Filled.Search, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(if (t.isStartPage) "Start page" else UrlInput.host(t.url), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton({ controller.close(t) }) { Icon(Icons.Filled.Close, "Close tab") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaSheet(tab: BrowserTab, onDismiss: () -> Unit, onPick: (DetectedMedia) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Media found on this page", style = MaterialTheme.typography.titleLarge)
            Text(tab.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            if (tab.media.isEmpty()) {
                Text("Play the video or song first; it shows up here as soon as it starts loading.", modifier = Modifier.padding(vertical = 24.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(tab.media.toList().asReversed(), key = { it.url }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = when (m.kind) {
                            DetectedMedia.Kind.VIDEO -> Icons.Filled.Movie
                            DetectedMedia.Kind.AUDIO -> Icons.Filled.MusicNote
                            DetectedMedia.Kind.STREAM -> Icons.Filled.Stream
                        }
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(
                                (if (m.kind == DetectedMedia.Kind.STREAM) "Stream (HLS)  •  " else "") + UrlInput.host(m.url),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                        Icon(Icons.Filled.Download, "Download", tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LinkListSheet(
    title: String, entries: List<Pair<String, String>>, onDismiss: () -> Unit,
    action: Pair<String, () -> Unit>? = null, onOpen: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                action?.let { (label, f) -> TextButton(f) { Text(label) } }
            }
            if (entries.isEmpty()) Text("Nothing here yet.", modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(entries) { (t, u) ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(u) }.padding(vertical = 10.dp)) {
                        Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(u, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkMenuSheet(
    url: String, imageUrl: String?, onDismiss: () -> Unit, onNewTab: () -> Unit, onBackgroundTab: () -> Unit,
    onDownload: (String) -> Unit, onCopy: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(url, Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            MenuRow(Icons.Filled.Download, "Download link") { onDownload(url) }
            if (imageUrl != null && imageUrl != url) MenuRow(Icons.Filled.Download, "Download image") { onDownload(imageUrl) }
            MenuRow(Icons.Filled.Add, "Open in new tab", onNewTab)
            MenuRow(Icons.Filled.Add, "Open in background tab", onBackgroundTab)
            MenuRow(Icons.Filled.Share, "Copy link", onCopy)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp)
    }
}
