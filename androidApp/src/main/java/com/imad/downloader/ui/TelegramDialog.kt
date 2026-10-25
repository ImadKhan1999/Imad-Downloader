package com.imad.downloader.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imad.downloader.R

const val TELEGRAM_URL = "https://t.me/ImadLabs"

/** Shown when the app opens until "Don't show this again" is ticked: invites people to the ImadLabs channel. */
@Composable
fun TelegramDialog(onClose: (dontShowAgain: Boolean) -> Unit) {
    val context = LocalContext.current
    var never by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onClose(never) },
        icon = { Image(painterResource(R.drawable.imadlabs), null, Modifier.size(72.dp)) },
        title = { Text("Join ImadLabs on Telegram", textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Get iDownloader news, updates and new apps from ImadLabs first.",
                    textAlign = TextAlign.Center, fontSize = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("t.me/ImadLabs", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { never = !never }.testTag("telegram-never"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(never, { never = it })
                    Text("Don't show this again", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button({
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onClose(never)
            }, Modifier.testTag("telegram-join")) { Text("Join channel") }
        },
        dismissButton = { TextButton({ onClose(never) }, Modifier.testTag("telegram-close")) { Text("Not now") } },
    )
}
