package app.ceres.reader.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ceres.reader.util.BookIdLink

/** Asks for a Fanqie book id or share link; prefilled from the clipboard when it holds one. */
@Composable
fun BookIdDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var input by remember {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
        mutableStateOf(clip?.takeIf { BookIdLink.parse(it) != null }?.trim().orEmpty())
    }
    val bookId = BookIdLink.parse(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("番茄书号导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("书号或分享链接") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (bookId != null) "书号：$bookId" else "粘贴番茄 App 的分享内容，或直接输入 book_id",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); bookId?.let(onImport) }, enabled = bookId != null) {
                Text("导入", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(4.dp),
        containerColor = LocalPalette.current.paper,
    )
}
