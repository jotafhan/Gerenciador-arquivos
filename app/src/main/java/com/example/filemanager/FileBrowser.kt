package com.example.filemanager

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
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
    data class ZipChoice(val file: File) : DialogState
    data class Details(val files: List<File>) : DialogState
}

/** Tela principal: o navegador de arquivos, com as ferramentas (lixeira, categorias etc.) por cima. */
@Composable
fun FileBrowserScreen() {
    val storage = remember { Environment.getExternalStorageDirectory() }
    var tool by rememberSaveable { mutableStateOf("") } // "" = nenhuma ferramenta aberta
    var toolChanges by remember { mutableIntStateOf(0) }
    var jumpTo by remember { mutableStateOf<String?>(null) }

    fun closeTool() {
        tool = ""
        toolChanges++
    }

    Box(Modifier.fillMaxSize()) {
        BrowserContent(
            refreshSignal = toolChanges,
            onOpenTool = { tool = it },
            jumpRequest = jumpTo,
            onJumpHandled = { jumpTo = null }
        )
        if (tool.isNotEmpty()) {
            Surface(Modifier.fillMaxSize()) {
                ToolHost(
                    tool = tool,
                    storage = storage,
                    onClose = { closeTool() },
                    onShowInFolder = { dir ->
                        jumpTo = dir.absolutePath
                        closeTool()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContent(
    refreshSignal: Int,
    onOpenTool: (String) -> Unit,
    jumpRequest: String?,
    onJumpHandled: () -> Unit
) {
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
    var moreOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    val current = File(currentPath)
    val sortKey = SortKey.valueOf(sortName)
    val canGoUp = currentPath != root.absolutePath && current.parentFile != null
    val title = if (canGoUp) current.name else "Armazenamento interno"
    val inSearch = searching && query.isNotBlank()

    val rawEntries = remember(currentPath, showHidden, refreshKey, refreshSignal) {
        FileOps.list(File(currentPath), showHidden)
    }

    // Tamanho das pastas (só calculado ao ordenar por tamanho; chega pasta por pasta)
    var folderSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var sizing by remember { mutableStateOf(false) }
    LaunchedEffect(rawEntries, sortName, refreshKey, refreshSignal) {
        if (sortName == SortKey.SIZE.name) {
            sizing = true
            folderSizes = emptyMap()
            for (d in rawEntries.filter { it.isDirectory }) {
                val bytes = withContext(Dispatchers.IO) { Scanner.measure(listOf(d)).bytes }
                folderSizes = folderSizes + (d.absolutePath to bytes)
            }
            sizing = false
        } else {
            folderSizes = emptyMap()
            sizing = false
        }
    }

    val folderEntries = remember(rawEntries, sortName, ascending, folderSizes) {
        FileOps.sort(rawEntries, sortKey, ascending, folderSizes)
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
            searchResults = FileOps.sort(found, sortKey, ascending, folderSizes)
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

    fun say(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    fun navigateTo(dir: File) {
        currentPath = dir.absolutePath
        selected = emptySet()
        searching = false
        query = ""
    }

    LaunchedEffect(jumpRequest) {
        jumpRequest?.let {
            navigateTo(File(it))
            onJumpHandled()
        }
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
                                DropdownMenuItem(
                                    text = { Text("Analisar espaço") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenTool(TOOL_SPACE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Achar duplicados") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenTool(TOOL_DUPLICATES)
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
                                                ascending = key == SortKey.NAME
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { dialog = DialogState.Delete(files) }) { Text("Excluir") }
                    Box {
                        TextButton(onClick = { moreOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            if (files.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Renomear") },
                                    onClick = {
                                        moreOpen = false
                                        dialog = DialogState.Rename(files.first())
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Detalhes") },
                                onClick = {
                                    moreOpen = false
                                    dialog = DialogState.Details(files)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Compactar em ZIP") },
                                onClick = {
                                    moreOpen = false
                                    val dest = current
                                    runOp("ZIP criado") {
                                        Zip.compress(files, dest).exceptionOrNull()
                                            ?.let { listOf(it.message ?: "Erro ao compactar") }
                                            ?: emptyList()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Compartilhar") },
                                onClick = {
                                    moreOpen = false
                                    if (files.any { it.isDirectory }) {
                                        say("Pastas não podem ser compartilhadas. Compacte em ZIP primeiro.")
                                    } else {
                                        FileOpener.share(context, files)
                                        selected = emptySet()
                                    }
                                }
                            )
                            val zipFile = files.singleOrNull()
                                ?.takeIf { it.isFile && it.extension.equals("zip", ignoreCase = true) }
                            if (zipFile != null) {
                                DropdownMenuItem(
                                    text = { Text("Extrair aqui") },
                                    onClick = {
                                        moreOpen = false
                                        val dest = zipFile.parentFile ?: current
                                        runOp("Extraído") {
                                            Zip.extract(zipFile, dest).exceptionOrNull()
                                                ?.let { listOf(it.message ?: "Erro ao extrair") }
                                                ?: emptyList()
                                        }
                                    }
                                )
                            }
                        }
                    }
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
            if (busy || sizing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

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
                    items(Category.entries.toList()) { cat ->
                        AssistChip(
                            onClick = { onOpenTool(cat.name) },
                            label = { Text("${cat.icon} ${cat.label}") }
                        )
                    }
                    item {
                        AssistChip(onClick = { onOpenTool(TOOL_TRASH) }, label = { Text("🗑️ Lixeira") })
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
                            folderSize = if (file.isDirectory) folderSizes[file.absolutePath] else null,
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
                                    file.extension.equals("zip", ignoreCase = true) -> {
                                        dialog = DialogState.ZipChoice(file)
                                    }
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
        is DialogState.Delete -> {
            val toDelete = d.files
            val allInTrash = toDelete.all { Trash.isInTrash(root, it) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Excluir ${toDelete.size} item(ns)?") },
                text = {
                    Text(
                        if (allInTrash) {
                            "Itens que já estão na lixeira são apagados de forma permanente."
                        } else {
                            "Na lixeira você pode restaurar depois. \"Excluir de vez\" apaga permanentemente."
                        }
                    )
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            dialog = null
                            runOp("Excluído de vez") {
                                toDelete.mapNotNull { f ->
                                    if (f.deleteRecursively()) null else "Não foi possível excluir ${f.name}"
                                }
                            }
                        }) { Text("Excluir de vez") }
                        if (!allInTrash) {
                            TextButton(onClick = {
                                dialog = null
                                runOp("Movido para a lixeira") {
                                    toDelete.mapNotNull { f ->
                                        if (Trash.isInTrash(root, f)) {
                                            if (f.deleteRecursively()) null else "Não foi possível excluir ${f.name}"
                                        } else {
                                            Trash.moveToTrash(root, f).exceptionOrNull()
                                                ?.let { "${f.name}: ${it.message}" }
                                        }
                                    }
                                }
                            }) { Text("Lixeira") }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }
        is DialogState.ZipChoice -> {
            val zipFile = d.file
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(zipFile.name) },
                text = { Text("O que fazer com este arquivo ZIP?") },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val dest = zipFile.parentFile ?: current
                        runOp("Extraído") {
                            Zip.extract(zipFile, dest).exceptionOrNull()
                                ?.let { listOf(it.message ?: "Erro ao extrair") }
                                ?: emptyList()
                        }
                    }) { Text("Extrair aqui") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = null
                        FileOpener.open(context, zipFile)
                    }) { Text("Abrir com outro app") }
                }
            )
        }
        is DialogState.Details -> DetailsDialog(files = d.files, onDismiss = { dialog = null })
        null -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrashScreen(storage: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Trash.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<Trash.Entry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        loading = true
        items = withContext(Dispatchers.IO) { Trash.list(storage) }
        loading = false
    }

    BackHandler { onClose() }

    /** Roda [work] em segundo plano; devolve uma mensagem de erro ou null se deu certo. */
    fun act(successMessage: String, work: () -> String?) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { work() }
            refreshKey++
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(error ?: successMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Lixeira") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                actions = {
                    TextButton(onClick = { confirmEmpty = true }, enabled = items.isNotEmpty()) {
                        Text("Esvaziar")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("A lixeira está vazia")
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.dir.name }) { entry ->
                        val date = remember(entry.dir.name) {
                            DateFormat.getDateTimeInstance().format(Date(entry.deletedAt))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = entry }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(iconFor(entry.item), style = MaterialTheme.typography.titleLarge)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val size = if (entry.item.isFile) {
                                    Formatter.formatFileSize(context, entry.item.length()) + "  •  "
                                } else ""
                                Text(
                                    text = "$size$date\nDe: ${entry.origin.substringBeforeLast('/')}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    picked?.let { entry ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(entry.item.name) },
            text = { Text("Local original:\n${entry.origin}") },
            confirmButton = {
                TextButton(onClick = {
                    picked = null
                    act("Restaurado") {
                        Trash.restore(entry).exceptionOrNull()?.let { "Erro: ${it.message ?: "falha ao restaurar"}" }
                    }
                }) { Text("Restaurar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    picked = null
                    act("Excluído de vez") {
                        if (Trash.purge(entry)) null else "Não foi possível excluir"
                    }
                }) { Text("Excluir de vez") }
            }
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Esvaziar a lixeira?") },
            text = { Text("Todos os itens serão apagados de forma permanente.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    act("Lixeira esvaziada") {
                        if (Trash.empty(storage)) null else "Alguns itens não puderam ser apagados"
                    }
                }) { Text("Esvaziar") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancelar") } }
        )
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

internal fun iconFor(file: File): String {
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
internal fun FileRow(
    file: File,
    extra: String?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    folderSize: Long? = null
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
        } else if (folderSize != null) {
            append(Formatter.formatFileSize(context, folderSize))
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
