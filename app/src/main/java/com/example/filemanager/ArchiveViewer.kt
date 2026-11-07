package com.example.filemanager

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class ViewerNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
)

private data class PendingAction(val node: ViewerNode?, val open: Boolean, val wrong: Boolean)

/** "Ver conteúdo": navega pelas pastas de dentro de um arquivo compactado sem extrair tudo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchiveViewerScreen(archive: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var entries by remember { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var prefix by rememberSaveable { mutableStateOf("") } // pasta atual dentro do arquivo ("" = raiz)
    var picked by remember { mutableStateOf<ViewerNode?>(null) }
    var task by remember { mutableStateOf<Pair<String, OpProgress>?>(null) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var password by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(archive) {
        val result = withContext(Dispatchers.IO) { Archives.list(archive) }
        entries = result.getOrNull()
        loadError = if (result.isFailure) (result.exceptionOrNull()?.message ?: "Não foi possível ler o arquivo") else null
    }

    BackHandler {
        if (prefix.isNotEmpty()) prefix = prefix.substringBeforeLast('/', "") else onClose()
    }

    // Itens da pasta atual (as pastas são deduzidas dos caminhos)
    val nodes = remember(entries, prefix) {
        val base = if (prefix.isEmpty()) "" else "$prefix/"
        val children = LinkedHashMap<String, ViewerNode>()
        for (e in entries.orEmpty()) {
            val p = e.path
            if (p.isEmpty() || !p.startsWith(base) || p == prefix) continue
            val rest = p.removePrefix(base)
            if (rest.isEmpty()) continue
            val first = rest.substringBefore('/')
            val childPath = base + first
            if (rest.contains('/') || e.isDir) {
                if (children[first] == null) children[first] = ViewerNode(first, childPath, true, 0L, e.modified)
            } else {
                children[first] = ViewerNode(first, childPath, false, e.size, e.modified)
            }
        }
        children.values.sortedWith(compareByDescending<ViewerNode> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun runExtract(node: ViewerNode?, openAfter: Boolean, pwd: String?) {
        val progress = OpProgress()
        val title = when {
            node == null -> "Extraindo tudo..."
            openAfter -> "Abrindo ${node.name}..."
            else -> "Extraindo ${node.name}..."
        }
        task = title to progress
        scope.launch {
            val previewDir = File(
                context.cacheDir,
                "archive_preview/${(archive.absolutePath + archive.lastModified()).hashCode()}"
            )
            val usedPassword = pwd ?: password
            val result = withContext(Dispatchers.IO) {
                if (node == null) {
                    Archives.extract(archive, archive.parentFile ?: archive, usedPassword, progress)
                } else {
                    val dest = if (openAfter) previewDir else Archives.defaultExtractDir(archive)
                    val matches: (String) -> Boolean = { raw ->
                        val n = raw.replace('\\', '/').trim('/')
                        n == node.path || n.startsWith(node.path + "/")
                    }
                    Archives.extractEntries(archive, dest, usedPassword, matches, progress)
                }
            }
            task = null
            val error = result.exceptionOrNull()
            when {
                error is PasswordRequiredException -> {
                    pending = PendingAction(node, openAfter, error.wrong)
                }
                error != null -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(error.message ?: "Erro ao extrair")
                }
                node != null && openAfter -> {
                    val file = File(previewDir, node.path)
                    if (file.isFile) FileOpener.open(context, file)
                }
                else -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar("Extraído em ${result.getOrNull()?.name ?: "a pasta do arquivo"}")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(archive.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (prefix.isEmpty()) "/" else "/$prefix",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (prefix.isNotEmpty()) prefix = prefix.substringBeforeLast('/', "") else onClose()
                    }) { Text("←") }
                },
                actions = {
                    TextButton(
                        enabled = entries != null && task == null,
                        onClick = { runExtract(null, false, null) }
                    ) { Text("Extrair tudo") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(loadError ?: "", modifier = Modifier.padding(24.dp))
                }
                entries == null -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Lendo o conteúdo do arquivo...", modifier = Modifier.padding(16.dp))
                }
                nodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pasta vazia")
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(nodes, key = { it.path }) { node ->
                        ViewerRow(
                            node = node,
                            onClick = { if (node.isDir) prefix = node.path else picked = node },
                            onMore = { picked = node }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    picked?.let { node ->
        val info = buildString {
            append("/").append(node.path)
            if (!node.isDir && node.size >= 0) append("\n").append(Formatter.formatFileSize(context, node.size))
        }
        val actions = if (node.isDir) {
            listOf(Act("Extrair esta pasta") { runExtract(node, false, null) })
        } else {
            listOf(
                Act("Abrir") { runExtract(node, true, null) },
                Act("Extrair") { runExtract(node, false, null) }
            )
        }
        ActionsDialog(title = node.name, info = info, actions = actions, onDismiss = { picked = null })
    }

    pending?.let { action ->
        PasswordDialog(
            title = "Arquivo protegido",
            message = if (action.wrong) "Senha incorreta. Tente de novo." else "Digite a senha para continuar.",
            onDismiss = { pending = null },
            onConfirm = { pwd ->
                pending = null
                password = pwd
                runExtract(action.node, action.open, pwd)
            }
        )
    }

    task?.let { (title, progress) ->
        ProgressDialog(title = title, progress = progress, onCancel = { progress.cancelled = true })
    }
}

@Composable
private fun ViewerRow(node: ViewerNode, onClick: () -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val emoji = if (node.isDir) "📁" else (categoryOf(File(node.name))?.icon ?: "📄")
    val date = remember(node.modified) {
        if (node.modified > 0) DateFormat.getDateTimeInstance().format(Date(node.modified)) else ""
    }
    val subtitle = buildString {
        if (!node.isDir && node.size >= 0) append(Formatter.formatFileSize(context, node.size))
        if (date.isNotEmpty()) {
            if (isNotEmpty()) append("  •  ")
            append(date)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onMore) { Text("⋮") }
    }
}
