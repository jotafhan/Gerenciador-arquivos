package com.example.filemanager

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class Clipboard(val paths: List<String>, val move: Boolean)

private sealed interface DialogState {
    data object NewFolder : DialogState
    data class Rename(val file: File) : DialogState
    data class Delete(val files: List<File>) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { Environment.getExternalStorageDirectory() }

    // Estado que sobrevive a girar a tela
    var currentPath by rememberSaveable { mutableStateOf(root.absolutePath) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(SortKey.NAME.name) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var clipboard by remember { mutableStateOf<Clipboard?>(null) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    val current = File(currentPath)
    val sortKey = SortKey.valueOf(sortName)
    val canGoUp = currentPath != root.absolutePath && current.parentFile != null
    val title = if (canGoUp) current.name else "Armazenamento interno"
    val inSearch = searching && query.isNotBlank()

    val folderEntries = remember(currentPath, showHidden, sortName, ascending, refreshKey) {
        FileOps.sort(FileOps.list(File(currentPath), showHidden), sortKey, ascending)
    }

    var searchResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchRunning by remember { mutableStateOf(false) }
    LaunchedEffect(searching, query, currentPath, showHidden, refreshKey) {
        if (searching && query.isNotBlank()) {
            searchRunning = true
            delay(350) // espera o usuário parar de digitar
            val found = withContext(Dispatchers.IO) {
                FileOps.search(File(currentPath), query, showHidden)
            }
            searchResults = FileOps.sort(found, sortKey, ascending)
            searchRunning = false
        } else {
            searchResults = emptyList()
            searchRunning = false
        }
    }
    LaunchedEffect(searching) {
        if (searching) runCatching { focusRequester.requestFocus() }
    }

    val entries = if (inSearch) searchResults else folderEntries

    val shortcuts = remember {
        buildList<Pair<String, File>> {
            add("Início" to root)
            listOf("Download", "DCIM", "Documents", "Pictures", "Movies", "Music").forEach { name ->
                val dir = File(root, name)
                if (dir.isDirectory) add(name to dir)
            }
        }
    }

    fun navigateTo(dir: File) {
        currentPath = dir.absolutePath
        selected = emptySet()
        searching = false
        query = ""
    }

    /** Roda [work] em segundo plano; ele devolve a lista de mensagens de erro (vazia = sucesso). */
    fun runOp(successMessage: String, work: () -> List<String>) {
        scope.launch {
            busy = true
            val errors = withContext(Dispatchers.IO) { work() }
            busy = false
            selected = emptySet()
            refreshKey++
            snackbar.currentSnackbarData?.dismiss()
            val msg = if (errors.isEmpty()) {
                successMessage
            } else {
                errors.first() + if (errors.size > 1) " (+${errors.size - 1} erros)" else ""
            }
            snackbar.showSnackbar(msg)
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = selected.isEmpty() && searching) {
        searching = false
        query = ""
    }
    BackHandler(enabled = selected.isEmpty() && !searching && canGoUp) {
        current.parentFile?.let { navigateTo(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selected.size} selecionado(s)") },
                    navigationIcon = {
                        TextButton(onClick = { selected = emptySet() }) { Text("✕") }
                    },
                    actions = {
                        TextButton(onClick = { selected = entries.map { it.absolutePath }.toSet() }) {
                            Text("Todos")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (canGoUp) {
                            TextButton(onClick = { current.parentFile?.let { navigateTo(it) } }) {
                                Text("←")
                            }
                        }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Buscar") },
                                    onClick = {
                                        menuOpen = false
                                        searching = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Nova pasta") },
                                    onClick = {
                                        menuOpen = false
                                        dialog = DialogState.NewFolder
                                    }
                                )
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
                                                ascending = true
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(if (showHidden) "Ocultar arquivos ocultos" else "Mostrar arquivos ocultos")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showHidden = !showHidden
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Atualizar") },
                                    onClick = {
                                        menuOpen = false
                                        refreshKey++
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            val cb = clipboard
            if (selected.isNotEmpty()) {
                val files = entries.filter { it.absolutePath in selected }
                BottomAppBar {
                    TextButton(onClick = {
                        clipboard = Clipboard(files.map { it.absolutePath }, move = false)
                        selected = emptySet()
                    }) { Text("Copiar") }
                    TextButton(onClick = {
                        clipboard = Clipboard(files.map { it.absolutePath }, move = true)
                        selected = emptySet()
                    }) { Text("Mover") }
                    if (files.size == 1) {
                        TextButton(onClick = { dialog = DialogState.Rename(files.first()) }) {
                            Text("Renomear")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { dialog = DialogState.Delete(files) }) { Text("Excluir") }
                }
            } else if (cb != null) {
                BottomAppBar {
                    Text(
                        text = "${cb.paths.size} para ${if (cb.move) "mover" else "copiar"}",
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { clipboard = null }) { Text("Cancelar") }
                    Button(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = {
                            val dest = current
                            clipboard = null
                            runOp("Pronto") {
                                cb.paths.mapNotNull { p ->
                                    val f = File(p)
                                    val r = if (cb.move) FileOps.move(f, dest) else FileOps.copy(f, dest)
                                    r.exceptionOrNull()?.let { "${f.name}: ${it.message}" }
                                }
                            }
                        }
                    ) { Text("Colar aqui") }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Buscar em $title") },
                    trailingIcon = {
                        TextButton(onClick = {
                            searching = false
                            query = ""
                        }) { Text("Fechar") }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .focusRequester(focusRequester)
                )
            } else if (selected.isEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shortcuts) { (label, dir) ->
                        AssistChip(onClick = { navigateTo(dir) }, label = { Text(label) })
                    }
                }
            }

            Text(
                text = current.absolutePath,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val message = when {
                searching && query.isBlank() -> "Digite para buscar"
                inSearch && searchRunning -> "Buscando..."
                entries.isEmpty() -> if (inSearch) "Nenhum resultado" else "Pasta vazia ou sem acesso"
                else -> null
            }

            if (message != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { file ->
                        FileRow(
                            file = file,
                            extra = if (inSearch) file.parent?.removePrefix(root.absolutePath) else null,
                            selectionMode = selected.isNotEmpty(),
                            isSelected = file.absolutePath in selected,
                            onClick = {
                                when {
                                    selected.isNotEmpty() -> {
                                        selected = if (file.absolutePath in selected) {
                                            selected - file.absolutePath
                                        } else {
                                            selected + file.absolutePath
                                        }
                                    }
                                    file.isDirectory -> navigateTo(file)
                                    else -> FileOpener.open(context, file)
                                }
                            },
                            onLongClick = {
                                selected = if (file.absolutePath in selected) {
                                    selected - file.absolutePath
                                } else {
                                    selected + file.absolutePath
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        is DialogState.NewFolder -> TextInputDialog(
            title = "Nova pasta",
            label = "Nome",
            initial = "",
            confirmLabel = "Criar",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                val dest = current
                runOp("Pasta criada") {
                    FileOps.createFolder(dest, name).exceptionOrNull()
                        ?.let { listOf(it.message ?: "Erro ao criar a pasta") }
                        ?: emptyList()
                }
            }
        )
        is DialogState.Rename -> TextInputDialog(
            title = "Renomear",
            label = "Novo nome",
            initial = d.file.name,
            confirmLabel = "Renomear",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                val target = d.file
                runOp("Renomeado") {
                    FileOps.rename(target, name).exceptionOrNull()
                        ?.let { listOf(it.message ?: "Erro ao renomear") }
                        ?: emptyList()
                }
            }
        )
        is DialogState.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Excluir ${d.files.size} item(ns)?") },
            text = { Text("Isso apaga de forma permanente. Ainda não existe lixeira.") },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = d.files
                    dialog = null
                    runOp("Excluído") {
                        toDelete.mapNotNull { f ->
                            if (f.deleteRecursively()) null else "Não foi possível excluir ${f.name}"
                        }
                    }
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
        )
        null -> {}
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun iconFor(file: File): String {
    if (file.isDirectory) return "📁"
    return when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "🖼️"
        "mp4", "mkv", "avi", "mov", "webm", "3gp" -> "🎬"
        "mp3", "flac", "wav", "ogg", "m4a", "aac" -> "🎵"
        "apk", "xapk", "apks" -> "🤖"
        "zip", "rar", "7z", "tar", "gz" -> "📦"
        else -> "📄"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: File,
    extra: String?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val modified = file.lastModified()
    val date = remember(file.absolutePath, modified) {
        DateFormat.getDateTimeInstance().format(Date(modified))
    }
    val subtitle = buildString {
        if (!file.isDirectory) {
            append(Formatter.formatFileSize(context, file.length()))
            append("  •  ")
        }
        append(date)
        if (!extra.isNullOrEmpty()) {
            append("\n")
            append(extra)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) Checkbox(checked = isSelected, onCheckedChange = null)
        Text(iconFor(file), style = MaterialTheme.typography.titleLarge)
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
