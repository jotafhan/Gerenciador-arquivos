package com.example.filemanager

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.text.NumberFormat
import java.util.Date

const val TOOL_TRASH = "TRASH"
const val TOOL_SPACE = "SPACE"
const val TOOL_DUPLICATES = "DUPLICATES"

private fun fmt(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

/** Escolhe qual ferramenta (tela cheia) mostrar por cima do navegador. */
@Composable
fun ToolHost(tool: String, storage: File, onClose: () -> Unit, onShowInFolder: (File) -> Unit) {
    when (tool) {
        TOOL_TRASH -> TrashScreen(storage = storage, onClose = onClose)
        TOOL_SPACE -> SpaceScreen(storage = storage, onClose = onClose, onShowInFolder = onShowInFolder)
        TOOL_DUPLICATES -> DuplicatesScreen(storage = storage, onClose = onClose)
        else -> {
            val cat = Category.entries.firstOrNull { it.name == tool }
            if (cat != null) {
                CategoryScreen(cat = cat, storage = storage, onClose = onClose, onShowInFolder = onShowInFolder)
            }
        }
    }
}

private class Act(val label: String, val run: () -> Unit)

private fun fileActions(
    context: Context,
    file: File,
    onShowInFolder: (File) -> Unit,
    onTrash: (File) -> Unit
): List<Act> = listOf(
    Act("Abrir") { FileOpener.open(context, file) },
    Act("Mostrar na pasta") { file.parentFile?.let { onShowInFolder(it) } },
    Act("Compartilhar") { FileOpener.share(context, listOf(file)) },
    Act("Mover para a lixeira") { onTrash(file) }
)

@Composable
private fun ActionsDialog(title: String, info: String, actions: List<Act>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(info, style = MaterialTheme.typography.bodySmall)
                actions.forEach { act ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            act.run()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(act.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
private fun SizeRow(
    icon: String,
    title: String,
    subtitle: String?,
    value: String,
    fraction: Float?,
    onClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(value, style = MaterialTheme.typography.labelLarge)
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

// ---------------------------------------------------------------- Categorias

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryScreen(
    cat: Category,
    storage: File,
    onClose: () -> Unit,
    onShowInFolder: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var found by remember { mutableStateOf<List<ScanItem>>(emptyList()) }
    var sorted by remember { mutableStateOf<List<ScanItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(SortKey.DATE.name) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(cat) {
        loading = true
        found = withContext(Dispatchers.IO) { Scanner.findCategory(storage, cat) }
        loading = false
    }
    LaunchedEffect(found, sortName, ascending) {
        sorted = withContext(Dispatchers.Default) {
            Scanner.sortItems(found, SortKey.valueOf(sortName), ascending)
        }
    }
    BackHandler { onClose() }

    val sortKey = SortKey.valueOf(sortName)
    val totalBytes = remember(sorted) { sorted.sumOf { it.size } }

    fun trash(file: File) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { Trash.moveToTrash(storage, file) }
            if (result.isSuccess) found = found.filter { it.file != file }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                if (result.isSuccess) "Movido para a lixeira"
                else "Erro: ${result.exceptionOrNull()?.message ?: "falha ao mover"}"
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("${cat.icon} ${cat.label}") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            SortKey.entries.forEach { key ->
                                val arrow = if (key == sortKey) {
                                    if (ascending) " ↑" else " ↓"
                                } else ""
                                DropdownMenuItem(
                                    text = { Text("Ordenar por ${key.label}$arrow") },
                                    onClick = {
                                        menuOpen = false
                                        if (key == sortKey) {
                                            ascending = !ascending
                                        } else {
                                            sortName = key.name
                                            ascending = key == SortKey.NAME
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Procurando arquivos...", modifier = Modifier.padding(16.dp))
            } else if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum arquivo encontrado")
                }
            } else {
                Text(
                    text = "${sorted.size} arquivo(s)  •  ${fmt(context, totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sorted, key = { it.file.absolutePath }) { item ->
                        FileRow(
                            file = item.file,
                            extra = item.file.parent?.removePrefix(storage.absolutePath),
                            selectionMode = false,
                            isSelected = false,
                            onClick = { FileOpener.open(context, item.file) },
                            onLongClick = { picked = item.file }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    picked?.let { f ->
        ActionsDialog(
            title = f.name,
            info = f.absolutePath,
            actions = fileActions(context, f, onShowInFolder) { trash(it) },
            onDismiss = { picked = null }
        )
    }
}

// ---------------------------------------------------------------- Espaço

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceScreen(storage: File, onClose: () -> Unit, onShowInFolder: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var report by remember { mutableStateOf<SpaceReport?>(null) }
    var picked by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { Scanner.analyzeSpace(storage) }
    }
    BackHandler { onClose() }

    fun trash(file: File) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { Trash.moveToTrash(storage, file) }
            if (result.isSuccess) {
                report = report?.let { r -> r.copy(bigFiles = r.bigFiles.filter { it != file }) }
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                if (result.isSuccess) "Movido para a lixeira"
                else "Erro: ${result.exceptionOrNull()?.message ?: "falha ao mover"}"
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Espaço") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } }
            )
        }
    ) { padding ->
        val r = report
        if (r == null) {
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Analisando o armazenamento... pode levar alguns segundos.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val used = (r.totalBytes - r.freeBytes).coerceAtLeast(0L)
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Armazenamento interno", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            progress = { if (r.totalBytes > 0) used.toFloat() / r.totalBytes else 0f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                        Text("Usado ${fmt(context, used)} de ${fmt(context, r.totalBytes)}  •  livre ${fmt(context, r.freeBytes)}")
                        Text(
                            "Os valores abaixo contam só os arquivos que o app consegue ler (${fmt(context, r.scannedBytes)}). " +
                                "O resto é sistema, apps e dados protegidos, como Android/data.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                item { SectionTitle("Por tipo de arquivo") }
                items(r.byCategory, key = { it.first }) { (label, bytes) ->
                    SizeRow(
                        icon = Category.entries.firstOrNull { it.label == label }?.icon ?: "📁",
                        title = label,
                        subtitle = null,
                        value = fmt(context, bytes),
                        fraction = if (r.scannedBytes > 0) bytes.toFloat() / r.scannedBytes else 0f,
                        onClick = null
                    )
                }
                item { SectionTitle("Maiores pastas (toque para abrir)") }
                items(r.folders, key = { it.first.absolutePath }) { (dir, bytes) ->
                    SizeRow(
                        icon = "📁",
                        title = dir.name,
                        subtitle = null,
                        value = fmt(context, bytes),
                        fraction = if (r.scannedBytes > 0) bytes.toFloat() / r.scannedBytes else 0f,
                        onClick = { onShowInFolder(dir) }
                    )
                }
                item { SectionTitle("Maiores arquivos (toque para ações)") }
                items(r.bigFiles, key = { it.absolutePath }) { f ->
                    SizeRow(
                        icon = iconFor(f),
                        title = f.name,
                        subtitle = f.parent?.removePrefix(storage.absolutePath),
                        value = fmt(context, f.length()),
                        fraction = null,
                        onClick = { picked = f }
                    )
                }
            }
        }
    }

    picked?.let { f ->
        ActionsDialog(
            title = f.name,
            info = f.absolutePath,
            actions = fileActions(context, f, onShowInFolder) { trash(it) },
            onDismiss = { picked = null }
        )
    }
}

// ---------------------------------------------------------------- Duplicados

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicatesScreen(storage: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var groups by remember { mutableStateOf<List<DupGroup>?>(null) }
    var status by remember { mutableStateOf("Procurando arquivos...") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) {
            Scanner.findDuplicates(storage) { status = it }
        }
    }
    BackHandler { onClose() }

    val current = groups
    val freed = current.orEmpty().sumOf { g -> g.files.count { it.absolutePath in selected } * g.size }

    fun deleteSelected() {
        val snapshot = groups ?: return
        val picked = selected
        scope.launch {
            busy = true
            val (doneSet, errors) = withContext(Dispatchers.IO) {
                val done = HashSet<String>()
                var failed = 0
                for (g in snapshot) {
                    val sel = g.files.filter { it.absolutePath in picked }
                    // Garante que pelo menos uma cópia de cada grupo continue existindo
                    val victims = if (sel.size == g.files.size) sel.drop(1) else sel
                    for (f in victims) {
                        if (Trash.moveToTrash(storage, f).isSuccess) done.add(f.absolutePath) else failed++
                    }
                }
                done to failed
            }
            groups = snapshot.mapNotNull { g ->
                val left = g.files.filter { it.absolutePath !in doneSet }
                if (left.size > 1) g.copy(files = left) else null
            }
            selected = emptySet()
            busy = false
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(
                "${doneSet.size} arquivo(s) movido(s) para a lixeira" +
                    if (errors > 0) " ($errors com erro)" else ""
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Duplicados") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                actions = {
                    TextButton(
                        enabled = !current.isNullOrEmpty() && !busy,
                        onClick = {
                            // Mantém a cópia mais antiga de cada grupo e marca as demais
                            selected = current.orEmpty()
                                .flatMap { g -> g.files.drop(1).map { it.absolutePath } }
                                .toSet()
                        }
                    ) { Text("Marcar cópias") }
                }
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        text = "${selected.size} selecionado(s)  •  libera ${fmt(context, freed)}",
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        enabled = !busy,
                        onClick = { confirmDelete = true }
                    ) { Text("Para a lixeira") }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                current == null -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(status, modifier = Modifier.padding(16.dp))
                }
                current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum arquivo duplicado (acima de 10 KB)")
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(current, key = { it.files.first().absolutePath }) { group ->
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                text = "${group.files.size} cópias  •  ${fmt(context, group.size)} cada  •  " +
                                    "desperdício ${fmt(context, group.wasted)}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            group.files.forEach { f ->
                                val isSel = f.absolutePath in selected
                                val date = DateFormat.getDateTimeInstance().format(Date(f.lastModified()))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = if (isSel) selected - f.absolutePath else selected + f.absolutePath
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = isSel, onCheckedChange = null)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${f.parent?.removePrefix(storage.absolutePath)}  •  $date",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Mover ${selected.size} arquivo(s) para a lixeira?") },
            text = {
                Text("Pelo menos uma cópia de cada grupo é mantida. Você pode restaurar pela lixeira.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleteSelected()
                }) { Text("Mover") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
        )
    }
}

// ---------------------------------------------------------------- Detalhes

@Composable
internal fun DetailsDialog(files: List<File>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var measure by remember { mutableStateOf<Measure?>(null) }

    LaunchedEffect(files) {
        measure = withContext(Dispatchers.IO) { Scanner.measure(files) }
    }

    val single = files.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(single?.name ?: "${files.size} itens", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (single != null) {
                    Text("Caminho: ${single.absolutePath}")
                    val type = if (single.isDirectory) {
                        "Pasta"
                    } else {
                        FileOpener.mimeOf(single).takeIf { it != "*/*" } ?: "Desconhecido"
                    }
                    Text("Tipo: $type")
                    Text("Modificado: ${DateFormat.getDateTimeInstance().format(Date(single.lastModified()))}")
                    Text(
                        "Permissões: leitura ${if (single.canRead()) "sim" else "não"}" +
                            "  •  escrita ${if (single.canWrite()) "sim" else "não"}"
                    )
                } else {
                    Text("${files.count { it.isDirectory }} pasta(s) e ${files.count { it.isFile }} arquivo(s) selecionados")
                }
                val m = measure
                if (m == null) {
                    Text("Calculando tamanho...")
                } else {
                    if (files.any { it.isDirectory }) {
                        Text("Conteúdo: ${m.files} arquivo(s) e ${m.folders} subpasta(s)")
                    }
                    Text("Tamanho total: ${fmt(context, m.bytes)} (${NumberFormat.getInstance().format(m.bytes)} bytes)")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
