package com.example.filemanager

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Lista o conteúdo de uma pasta: pastas primeiro, depois arquivos, em ordem alfabética. */
fun listEntries(dir: File, showHidden: Boolean): List<File> =
    dir.listFiles()
        ?.filter { showHidden || !it.name.startsWith(".") }
        ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        ?: emptyList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen() {
    val root = remember { Environment.getExternalStorageDirectory() }
    var current by remember { mutableStateOf(root) }
    var showHidden by remember { mutableStateOf(false) }
    val entries = remember(current, showHidden) { listEntries(current, showHidden) }

    val canGoUp = current.absolutePath != root.absolutePath
    BackHandler(enabled = canGoUp) {
        current.parentFile?.let { current = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (canGoUp) current.name else "Armazenamento interno",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "Ocultar ocultos" else "Mostrar ocultos")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                text = current.absolutePath,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pasta vazia ou sem acesso")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { file ->
                        FileRow(file = file, onOpenFolder = { current = it })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onOpenFolder: (File) -> Unit) {
    val context = LocalContext.current
    val date = remember(file) { DateFormat.getDateTimeInstance().format(Date(file.lastModified())) }
    val subtitle = if (file.isDirectory) {
        date
    } else {
        Formatter.formatFileSize(context, file.length()) + "  •  " + date
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = file.isDirectory) { onOpenFolder(file) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (file.isDirectory) "📁" else "📄", style = MaterialTheme.typography.titleLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Acesso aos arquivos", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Para listar e gerenciar seus arquivos, o app precisa de permissão de acesso ao armazenamento.",
                modifier = Modifier.padding(vertical = 16.dp)
            )
            Button(onClick = onRequest) { Text("Conceder acesso") }
        }
    }
}
