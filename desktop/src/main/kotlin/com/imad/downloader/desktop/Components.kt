package com.imad.downloader.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

val LocalInlineDialogs = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Standard dialog frame: app icon, theme, padding, Esc to close. */
@Composable
fun AppDialog(
    title: String,
    width: Dp,
    height: Dp,
    theme: ThemeMode,
    onClose: () -> Unit,
    resizable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalInlineDialogs.current) {
        // UI snapshots: draw the dialog body in place instead of opening an OS window.
        ImadTheme(theme) {
            Surface(Modifier.size(width, height), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp), content = content)
            }
        }
        return
    }
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(width, height)),
        title = title,
        icon = painterResource("icons/app.png"),
        resizable = resizable,
        onPreviewKeyEvent = { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onClose(); true } else false },
    ) {
        ImadTheme(theme) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(16.dp), content = content)
            }
        }
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    readOnly: Boolean = false,
    password: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 32.dp,
) {
    val p = LocalPalette.current
    Box(
        modifier.height(if (singleLine) minHeight else maxOf(minHeight, 64.dp)).clip(RoundedCornerShape(5.dp))
            .background(if (readOnly) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
            .padding(horizontal = 9.dp, vertical = if (singleLine) 0.dp else 7.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = p.muted, fontSize = 13.sp)
        BasicTextField(
            value = value,
            onValueChange = { if (!numeric || it.all(Char::isDigit)) onChange(it) },
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = CellText.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun FormRow(label: String, labelWidth: Dp = 120.dp, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(labelWidth), fontSize = 13.sp)
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.size(34.dp), colors = CheckboxDefaults.colors())
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
fun <T> Dropdown(value: T, options: List<T>, label: (T) -> String, modifier: Modifier = Modifier, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                .clickable { open = true }.padding(start = 9.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(value), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o), fontSize = 13.sp) }, onClick = { onChange(o); open = false }) }
        }
    }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = LocalPalette.current.muted, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
fun VSpace(h: Dp = 8.dp) = Spacer(Modifier.height(h))
