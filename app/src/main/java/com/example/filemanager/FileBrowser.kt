package com.example.filemanager

import android.content.Context
import android.os.Environment
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.sp
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
    data class Compress(val files: List<File>) : DialogState
    data class CopyMoveTo(val files: List<File>, val move: Boolean) : DialogState
    data class Metadata(val file: File) : DialogState
    data class MoveUp(val dir: File) : DialogState
    data object NewFile : DialogState
    data class AddTo(val files: List<File>) : DialogState
    data class ExtractPassword(val file: File, val dest: File, val wrong: Boolean) : DialogState
    data class InstallApk(val file: File) : DialogState
}

/** Tela principal: o início (home) e o navegador de arquivos, com as ferramentas por cima. */
@Composable
fun FileBrowserScreen() {
    val storage = remember { Environment.getExternalStorageDirectory() }
    var home by rememberSaveable { mutableStateOf(true) }
    var tool by rememberSaveable { mutableStateOf("") } // "" = nenhuma ferramenta aberta
    var toolChanges by remember { mutableIntStateOf(0) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var viewArchive by rememberSaveable { mutableStateOf<String?>(null) }

    fun closeTool() {
        tool = ""
        toolChanges++
    }

    // Abre a pasta pedida por um atalho da tela inicial do Android
    val launchPath = LaunchRequest.path
    LaunchedEffect(launchPath) {
        if (launchPath != null) {
            val target = File(launchPath)
            val dir = if (target.isDirectory) target else target.parentFile
            if (dir != null) jumpTo = dir.absolutePath
            home = false
            tool = ""
            LaunchRequest.path = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        BrowserContent(
            refreshSignal = toolChanges,
            onOpenTool = { tool = it },
            jumpRequest = jumpTo,
            onJumpHandled = { jumpTo = null },
            active = !home && tool.isEmpty() && viewArchive == null,
            onGoHome = { home = true },
            onViewArchive = { viewArchive = it.absolutePath }
        )
        if (home) {
            Surface(Modifier.fillMaxSize()) {
                HomeScreen(
                    storage = storage,
                    onOpenFolder = { dir ->
                        jumpTo = dir.absolutePath
                        home = false
                    },
                    onOpenTool = { tool = it }
                )
            }
        }
        viewArchive?.let { path ->
            Surface(Modifier.fillMaxSize()) {
                ArchiveViewerScreen(archive = File(path), onClose = { viewArchive = null })
            }
        }
        if (tool.isNotEmpty()) {
            Surface(Modifier.fillMaxSize()) {
                ToolHost(
                    tool = tool,
                    storage = storage,
                    onClose = { closeTool() },
                    onOpenTool = { tool = it },
                    onShowInFolder = { dir ->
                        jumpTo = dir.absolutePath
                        home = false
                        closeTool()
                    }
                )
            }
        }

        ActiveTask.current?.let { (title, progress) ->
            if (ActiveTask.hidden) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                ) {
                    ProgressBanner(
                        title = title,
                        progress = progress,
                        onShow = { ActiveTask.hidden = false },
                        onCancel = { progress.cancelled = true }
                    )
                }
            } else {
                ProgressDialog(
                    title = title,
                    progress = progress,
                    onCancel = { progress.cancelled = true },
                    onHide = { ActiveTask.hidden = true }
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
    onJumpHandled: () -> Unit,
    active: Boolean,
    onGoHome: () -> Unit,
    onViewArchive: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { Environment.getExternalStorageDirectory() }

    // Estado que sobrevive a girar a tela
    var currentPath by rememberSaveable { mutableStateOf(root.absolutePath) }
    var showHidden by rememberSaveable { mutableStateOf(AppSettings.showHiddenDefault) }
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
    var selectMenuOpen by remember { mutableStateOf(false) }

    // Guias: guarda as inativas (a guia ativa é o estado "ao vivo" desta tela)
    var tabsRaw by rememberSaveable { mutableStateOf("") }
    var activeTab by rememberSaveable { mutableStateOf(0) }
    val tabs = remember(tabsRaw) { decodeTabs(tabsRaw) }
    var selectedBytes by remember { mutableStateOf<Long?>(null) }

    // Tamanho total dos itens selecionados (pastas incluem o conteúdo)
    LaunchedEffect(selected) {
        selectedBytes = null
        if (selected.isNotEmpty()) {
            delay(150)
            selectedBytes = withContext(Dispatchers.IO) { Scanner.measure(selected.map { File(it) }).bytes }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val gridMode = AppSettings.gridMode
    var newMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // Ao mudar a ordenação (ou de pasta), volta ao primeiro item da lista
    LaunchedEffect(currentPath, sortName, ascending) {
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    val current = File(currentPath)
    val sortKey = SortKey.valueOf(sortName)
    val canGoUp = currentPath != root.absolutePath && current.parentFile != null
    val title = if (canGoUp) current.name else "Armazenamento interno"
    val inSearch = searching && query.isNotBlank()

    // A listagem é lida fora da thread principal: no armazenamento do Android cada arquivo
    // consultado custa uma chamada lenta ao sistema, e fazer isso ao rolar travava a lista.
    var listing by remember { mutableStateOf<Pair<String, List<FileEntry>>>("" to emptyList()) }
    var loadingList by remember { mutableStateOf(false) }
    LaunchedEffect(currentPath, showHidden, refreshKey, refreshSignal) {
        loadingList = true
        val dir = File(currentPath)
        val result = withContext(Dispatchers.IO) { FileOps.listEntries(dir, showHidden) }
        listing = dir.absolutePath to result
        loadingList = false
    }
    val rawEntries: List<FileEntry> = if (listing.first == currentPath) listing.second else emptyList()
    val metaByPath = remember(rawEntries) { rawEntries.associateBy { it.file.absolutePath } }

    // Tamanho das pastas (só calculado ao ordenar por tamanho; chega pasta por pasta)
    var folderSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var sizing by remember { mutableStateOf(false) }
    LaunchedEffect(rawEntries, sortName, refreshKey, refreshSignal) {
        if (sortName == SortKey.SIZE.name) {
            sizing = true
            folderSizes = emptyMap()
            for (d in rawEntries.filter { it.isDir }) {
                val bytes = withContext(Dispatchers.IO) { Scanner.measure(listOf(d.file)).bytes }
                folderSizes = folderSizes + (d.file.absolutePath to bytes)
            }
            sizing = false
        } else {
            folderSizes = emptyMap()
            sizing = false
        }
    }

    val folderEntries = remember(rawEntries, sortName, ascending, folderSizes) {
        FileOps.sortEntries(rawEntries, sortKey, ascending, folderSizes).map { it.file }
    }

    var searchResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var searchMeta by remember { mutableStateOf<Map<String, FileEntry>>(emptyMap()) }
    var searchRunning by remember { mutableStateOf(false) }
    LaunchedEffect(searching, query, currentPath, showHidden, refreshKey) {
        if (searching && query.isNotBlank()) {
            searchRunning = true
            delay(350) // espera o usuário parar de digitar
            val sorted = withContext(Dispatchers.IO) {
                val found = FileOps.search(File(currentPath), query, showHidden).map { FileOps.entryOf(it) }
                FileOps.sortEntries(found, sortKey, ascending, folderSizes)
            }
            searchResults = sorted.map { it.file }
            searchMeta = sorted.associateBy { it.file.absolutePath }
            searchRunning = false
        } else {
            searchResults = emptyList()
            searchMeta = emptyMap()
            searchRunning = false
        }
    }
    LaunchedEffect(searching) {
        if (searching) runCatching { focusRequester.requestFocus() }
    }

    val entries = if (inSearch) searchResults else folderEntries
    val metaOf = if (inSearch) searchMeta else metaByPath

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

    /** Roda uma operação longa em segundo plano, com janela de progresso (que pode ser ocultada). */
    fun runArchiveOp(title: String, successMessage: String, work: (OpProgress) -> List<String>) {
        if (ActiveTask.current != null) {
            say("Já existe uma operação em andamento")
            return
        }
        val progress = OpProgress()
        ActiveTask.start(context, title, progress)
        scope.launch {
            busy = true
            var wasHidden = false
            val errors = withContext(Dispatchers.IO) {
                var result = listOf("Erro")
                try {
                    result = work(progress)
                } catch (e: OperationCancelledException) {
                    result = listOf("Cancelado")
                } catch (e: Exception) {
                    result = listOf(e.message ?: "Erro")
                } finally {
                    wasHidden = ActiveTask.finish(
                        if (result.isEmpty()) successMessage else result.first(),
                        result.isEmpty()
                    )
                }
                result
            }
            busy = false
            selected = emptySet()
            refreshKey++
            val msg = if (errors.isEmpty()) successMessage else errors.first()
            if (wasHidden) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    /** Extrai um arquivo compactado; se pedir senha, abre o diálogo de senha. */
    fun extractArchive(file: File, dest: File, password: String?) {
        if (ActiveTask.current != null) {
            say("Já existe uma operação em andamento")
            return
        }
        val progress = OpProgress()
        ActiveTask.start(context, "Extraindo ${file.name}...", progress)
        scope.launch {
            busy = true
            var wasHidden = false
            val result = withContext(Dispatchers.IO) {
                val r = try {
                    Archives.extract(file, dest, password, progress)
                } catch (e: Exception) {
                    Result.failure(e)
                }
                val err = r.exceptionOrNull()
                val msg = when {
                    err == null -> "Extraído"
                    err is PasswordRequiredException -> null
                    else -> err.message ?: "Erro ao extrair"
                }
                wasHidden = ActiveTask.finish(msg, err == null)
                r
            }
            busy = false
            selected = emptySet()
            refreshKey++
            val error = result.exceptionOrNull()
            if (error is PasswordRequiredException) {
                dialog = DialogState.ExtractPassword(file, dest, error.wrong)
            } else {
                val msg = if (error == null) "Extraído" else (error.message ?: "Erro ao extrair")
                if (wasHidden) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(msg)
            }
        }
    }

    fun liveTab() = TabData(currentPath, sortName, ascending, showHidden)

    fun applyTabs(list: List<TabData>, newActive: Int) {
        tabsRaw = encodeTabs(list)
        activeTab = newActive
        val t = list[newActive]
        currentPath = t.path
        sortName = t.sort
        ascending = t.ascending
        showHidden = t.hidden
        selected = emptySet()
        searching = false
        query = ""
    }

    fun tabListWithLive(): MutableList<TabData> {
        val list = tabs.toMutableList()
        if (list.isEmpty()) list.add(liveTab())
        list[activeTab.coerceIn(0, list.lastIndex)] = liveTab()
        return list
    }

    fun switchTab(index: Int) {
        val list = tabListWithLive()
        applyTabs(list, index.coerceIn(0, list.lastIndex))
    }

    fun newTab(path: String) {
        val list = tabListWithLive()
        list.add(TabData(path, sortName, ascending, showHidden))
        applyTabs(list, list.lastIndex)
    }

    fun closeTab(index: Int) {
        val list = tabListWithLive()
        if (list.size <= 1 || index !in list.indices) return
        list.removeAt(index)
        val newActive = when {
            index < activeTab -> activeTab - 1
            index == activeTab -> minOf(index, list.lastIndex)
            else -> activeTab
        }
        applyTabs(list, newActive.coerceIn(0, list.lastIndex))
    }

    BackHandler(enabled = active && selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(enabled = active && selected.isEmpty() && searching) {
        searching = false
        query = ""
    }
    BackHandler(enabled = active && selected.isEmpty() && !searching && canGoUp) {
        current.parentFile?.let { navigateTo(it) }
    }
    BackHandler(enabled = active && selected.isEmpty() && !searching && !canGoUp) {
        // Com várias guias, voltar na raiz fecha a guia; com uma só, vai para o Início
        if (tabs.size > 1) closeTab(activeTab) else onGoHome()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                val files = entries.filter { it.absolutePath in selected }
                val one = files.singleOrNull()
                val sel = selected
                val sizeText = selectedBytes?.let { Formatter.formatFileSize(context, it) } ?: "…"
                TopAppBar(
                    navigationIcon = {
                        Box {
                            TextButton(onClick = { selectMenuOpen = true }) { Text("☑ ▾") }
                            DropdownMenu(expanded = selectMenuOpen, onDismissRequest = { selectMenuOpen = false }) {
                                val all = entries.map { it.absolutePath }.toSet()
                                fun choose(newSelection: Set<String>) {
                                    selected = newSelection
                                    selectMenuOpen = false
                                }
                                DropdownMenuItem(
                                    text = { Text("Alternar") },
                                    onClick = { choose(if (sel.size == all.size) emptySet() else all) }
                                )
                                DropdownMenuItem(text = { Text("Inverter") }, onClick = { choose(all - sel) })
                                DropdownMenuItem(
                                    text = { Text("Anterior") },
                                    onClick = {
                                        val i = entries.indexOfFirst { it.absolutePath in sel }
                                        choose(if (i > 0) sel + entries[i - 1].absolutePath else sel)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Próxima") },
                                    onClick = {
                                        val i = entries.indexOfLast { it.absolutePath in sel }
                                        choose(if (i >= 0 && i < entries.size - 1) sel + entries[i + 1].absolutePath else sel)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Apenas arquivos") },
                                    onClick = { choose(entries.filter { it.isFile }.map { it.absolutePath }.toSet()) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mesma extensão") },
                                    onClick = {
                                        val exts = files.filter { it.isFile }.map { it.extension.lowercase() }.toSet()
                                        choose(
                                            if (exts.isEmpty()) sel
                                            else entries.filter { it.isFile && it.extension.lowercase() in exts }
                                                .map { it.absolutePath }.toSet()
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mesmo tipo") },
                                    onClick = {
                                        val kinds = files.map { typeKey(it) }.toSet()
                                        choose(entries.filter { typeKey(it) in kinds }.map { it.absolutePath }.toSet())
                                    }
                                )
                                DropdownMenuItem(text = { Text("Nenhum") }, onClick = { choose(emptySet()) })
                                DropdownMenuItem(text = { Text("Todos") }, onClick = { choose(all) })
                            }
                        }
                    },
                    title = {
                        Column {
                            Text("${selected.size}", style = MaterialTheme.typography.titleMedium)
                            Text(sizeText, style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { dialog = DialogState.Delete(files) },
                            modifier = Modifier.size(40.dp)
                        ) { Text("🗑️") }
                        IconButton(
                            onClick = {
                                clipboard = Clipboard(files.map { it.absolutePath }, move = false)
                                selected = emptySet()
                            },
                            modifier = Modifier.size(40.dp)
                        ) { Text("📋") }
                        IconButton(
                            onClick = {
                                clipboard = Clipboard(files.map { it.absolutePath }, move = true)
                                selected = emptySet()
                            },
                            modifier = Modifier.size(40.dp)
                        ) { Text("✂️") }
                        if (one != null) {
                            IconButton(
                                onClick = { dialog = DialogState.Rename(one) },
                                modifier = Modifier.size(40.dp)
                            ) { Text("✏️") }
                        }
                        IconButton(
                            onClick = { dialog = DialogState.Details(files) },
                            modifier = Modifier.size(40.dp)
                        ) { Text("ℹ️") }
                        Box {
                            IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(40.dp)) { Text("⋮") }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
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
                                if (one != null) {
                                    DropdownMenuItem(
                                        text = { Text("Abrir") },
                                        onClick = {
                                            moreOpen = false
                                            if (one.isDirectory) navigateTo(one) else FileOpener.open(context, one)
                                        }
                                    )
                                    if (one.isDirectory) {
                                        DropdownMenuItem(
                                            text = { Text("Abrir em nova guia") },
                                            onClick = {
                                                moreOpen = false
                                                newTab(one.absolutePath)
                                            }
                                        )
                                    }
                                    if (one.isFile) {
                                        DropdownMenuItem(
                                            text = { Text("Abrir com...") },
                                            onClick = {
                                                moreOpen = false
                                                FileOpener.openWith(context, one)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Metadados") },
                                            onClick = {
                                                moreOpen = false
                                                dialog = DialogState.Metadata(one)
                                            }
                                        )
                                    }
                                }
                                val allFavorite = files.all { it.absolutePath in Favorites.items }
                                DropdownMenuItem(
                                    text = { Text(if (allFavorite) "Desafixar" else "Fixar") },
                                    onClick = {
                                        moreOpen = false
                                        if (allFavorite) {
                                            Favorites.remove(context, files)
                                            say("Removido dos favoritos")
                                        } else {
                                            Favorites.add(context, files)
                                            say("Fixado nos favoritos")
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Adicionar a...") },
                                    onClick = {
                                        moreOpen = false
                                        dialog = DialogState.AddTo(files)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Compactar...") },
                                    onClick = {
                                        moreOpen = false
                                        dialog = DialogState.Compress(files)
                                    }
                                )
                                if (one != null && Archives.isArchive(one)) {
                                    DropdownMenuItem(
                                        text = { Text("Ver conteúdo") },
                                        onClick = {
                                            moreOpen = false
                                            onViewArchive(one)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Extrair aqui") },
                                        onClick = {
                                            moreOpen = false
                                            val dest = one.parentFile ?: current
                                            extractArchive(one, dest, null)
                                        }
                                    )
                                }
                                val allHidden = files.all { it.name.startsWith(".") }
                                DropdownMenuItem(
                                    text = { Text(if (allHidden) "Mostrar" else "Ocultar") },
                                    onClick = {
                                        moreOpen = false
                                        runOp(
                                            if (allHidden) "Itens visíveis"
                                            else "Ocultado. Para ver, ative Mostrar arquivos ocultos"
                                        ) {
                                            files.mapNotNull { f ->
                                                val newName = if (allHidden) f.name.trimStart('.') else "." + f.name
                                                FileOps.rename(f, newName).exceptionOrNull()
                                                    ?.let { "${f.name}: ${it.message}" }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Copiar para...") },
                                    onClick = {
                                        moreOpen = false
                                        dialog = DialogState.CopyMoveTo(files, move = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mover para...") },
                                    onClick = {
                                        moreOpen = false
                                        dialog = DialogState.CopyMoveTo(files, move = true)
                                    }
                                )
                                if (one != null && one.isDirectory) {
                                    DropdownMenuItem(
                                        text = { Text("Mover conteúdo para cima") },
                                        onClick = {
                                            moreOpen = false
                                            dialog = DialogState.MoveUp(one)
                                        }
                                    )
                                }
                            }
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
                        } else {
                            TextButton(onClick = onGoHome) { Text("⌂") }
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
                                DropdownMenuItem(
                                    text = { Text("Nova guia") },
                                    onClick = {
                                        menuOpen = false
                                        newTab(currentPath)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Analisar esta pasta") },
                                    onClick = {
                                        menuOpen = false
                                        AnalysisTarget.path = current.absolutePath
                                        onOpenTool(TOOL_SPACE)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Configurações") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenTool(TOOL_SETTINGS)
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
                // As ações da seleção ficam na barra do topo
            } else if (cb != null) {
                CompactBottomBar {
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
            } else {
                CompactBottomBar {
                    IconButton(onClick = { searching = true }, modifier = Modifier.weight(1f)) {
                        Text("🔍", fontSize = 20.sp)
                    }
                    IconButton(
                        onClick = {
                            AppSettings.gridMode = !gridMode
                            prefs.edit().putBoolean("grid_mode", AppSettings.gridMode).apply()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (gridMode) "☰" else "▦", fontSize = 20.sp) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { newMenuOpen = true }) { Text("➕", fontSize = 20.sp) }
                        DropdownMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Nova pasta") },
                                onClick = {
                                    newMenuOpen = false
                                    dialog = DialogState.NewFolder
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Novo arquivo") },
                                onClick = {
                                    newMenuOpen = false
                                    dialog = DialogState.NewFile
                                }
                            )
                        }
                    }
                    IconButton(onClick = { refreshKey++ }, modifier = Modifier.weight(1f)) {
                        Text("🔄", fontSize = 20.sp)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { sortMenuOpen = true }) { Text("↕️", fontSize = 20.sp) }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortKey.entries.forEach { key ->
                                val arrow = if (key == sortKey) {
                                    if (ascending) " ↑" else " ↓"
                                } else ""
                                DropdownMenuItem(
                                    text = { Text("Ordenar por ${key.label}$arrow") },
                                    onClick = {
                                        sortMenuOpen = false
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
                    IconButton(
                        onClick = { selected = entries.map { it.absolutePath }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) { Text("☑", fontSize = 20.sp) }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy || sizing || loadingList) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (tabs.size > 1) {
                val livePath = currentPath
                val titles = tabs.mapIndexed { i, t ->
                    val path = if (i == activeTab) livePath else t.path
                    if (path == root.absolutePath) "Armazenamento" else File(path).name.ifEmpty { "/" }
                }
                TabStrip(
                    titles = titles,
                    active = activeTab.coerceIn(0, titles.lastIndex),
                    onSelect = { switchTab(it) },
                    onClose = { closeTab(it) },
                    onNew = { newTab(currentPath) }
                )
            }

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
                loadingList && !inSearch && entries.isEmpty() -> "Carregando..."
                entries.isEmpty() -> if (inSearch) "Nenhum resultado" else "Pasta vazia ou sem acesso"
                else -> null
            }

            if (message != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
            } else {
                if (gridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(96.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(6.dp)
                    ) {
                        gridItems(entries, key = { it.absolutePath }) { file ->
                            val entry = metaOf[file.absolutePath]
                            FileCell(
                                file = file,
                                folderSize = if (entry?.isDir == true) folderSizes[file.absolutePath] else null,
                                entry = entry,
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
                                        ApkInstaller.isInstallable(file) -> {
                                            if (AppSettings.confirmInstall) {
                                                dialog = DialogState.InstallApk(file)
                                            } else {
                                                ApkInstaller.install(context, file)?.let { say(it) }
                                            }
                                        }
                                        Archives.isArchive(file) -> {
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
                        }
                    }
                } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.absolutePath }) { file ->
                        val entry = metaOf[file.absolutePath]
                        FileRow(
                            file = file,
                            extra = if (inSearch) file.parent?.removePrefix(root.absolutePath) else null,
                            selectionMode = selected.isNotEmpty(),
                            isSelected = file.absolutePath in selected,
                            folderSize = if (entry?.isDir == true) folderSizes[file.absolutePath] else null,
                            entry = entry,
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
                                    ApkInstaller.isInstallable(file) -> {
                                        if (AppSettings.confirmInstall) {
                                            dialog = DialogState.InstallApk(file)
                                        } else {
                                            ApkInstaller.install(context, file)?.let { say(it) }
                                        }
                                    }
                                    Archives.isArchive(file) -> {
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
            DeleteDialog(
                files = toDelete,
                allInTrash = allInTrash,
                onDismiss = { dialog = null },
                onConfirm = { toTrash ->
                    dialog = null
                    runOp(if (toTrash) "Movido para a lixeira" else "Excluído de vez") {
                        toDelete.mapNotNull { f ->
                            if (!toTrash || Trash.isInTrash(root, f)) {
                                if (f.deleteRecursively()) null else "Não foi possível excluir ${f.name}"
                            } else {
                                Trash.moveToTrash(root, f).exceptionOrNull()
                                    ?.let { "${f.name}: ${it.message}" }
                            }
                        }
                    }
                }
            )
        }
        is DialogState.ZipChoice -> {
            val zipFile = d.file
            ActionsDialog(
                title = zipFile.name,
                info = "Arquivo compactado",
                actions = listOf(
                    Act("Ver conteúdo") { onViewArchive(zipFile) },
                    Act("Extrair aqui") { extractArchive(zipFile, zipFile.parentFile ?: current, null) },
                    Act("Abrir com outro app") { FileOpener.open(context, zipFile) }
                ),
                onDismiss = { dialog = null }
            )
        }
        is DialogState.Compress -> {
            val toPack = d.files
            CompressDialog(
                files = toPack,
                root = root,
                initialDest = current,
                onDismiss = { dialog = null },
                onConfirm = { dest, name, options ->
                    dialog = null
                    runArchiveOp("Compactando...", "Arquivo criado") { progress ->
                        val error = Archives.compress(toPack, dest, name, options, progress).exceptionOrNull()
                        if (error != null) {
                            listOf(error.message ?: "Erro ao compactar")
                        } else if (options.deleteAfter) {
                            toPack.mapNotNull { f ->
                                Trash.moveToTrash(root, f).exceptionOrNull()?.let { "${f.name}: ${it.message}" }
                            }
                        } else {
                            emptyList()
                        }
                    }
                }
            )
        }
        is DialogState.CopyMoveTo -> {
            val items = d.files
            val move = d.move
            FolderPickerDialog(
                title = if (move) "Mover para..." else "Copiar para...",
                root = root,
                start = current,
                confirmLabel = if (move) "Mover aqui" else "Copiar aqui",
                onDismiss = { dialog = null },
                onPick = { dest ->
                    dialog = null
                    runOp(if (move) "Movido" else "Copiado") {
                        items.mapNotNull { f ->
                            val r = if (move) FileOps.move(f, dest) else FileOps.copy(f, dest)
                            r.exceptionOrNull()?.let { "${f.name}: ${it.message}" }
                        }
                    }
                }
            )
        }
        is DialogState.Metadata -> MetadataDialog(file = d.file, onDismiss = { dialog = null })
        is DialogState.MoveUp -> {
            val dir = d.dir
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Mover conteúdo para cima?") },
                text = {
                    Text("Os itens de \"${dir.name}\" vão para a pasta acima e a pasta, se ficar vazia, é removida.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        runOp("Conteúdo movido") {
                            FileOps.moveContentUp(dir).exceptionOrNull()
                                ?.let { listOf(it.message ?: "Erro ao mover o conteúdo") }
                                ?: emptyList()
                        }
                    }) { Text("Mover") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }
        is DialogState.NewFile -> TextInputDialog(
            title = "Novo arquivo",
            label = "Nome (ex.: nota.txt)",
            initial = "",
            confirmLabel = "Criar",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                val dest = current
                runOp("Arquivo criado") {
                    FileOps.createFile(dest, name).exceptionOrNull()
                        ?.let { listOf(it.message ?: "Erro ao criar o arquivo") }
                        ?: emptyList()
                }
            }
        )
        is DialogState.AddTo -> {
            val items = d.files
            ActionsDialog(
                title = "Adicionar a...",
                info = "${items.size} item(ns)",
                actions = listOf(
                    Act("Favoritos") {
                        Favorites.add(context, items)
                        say("Adicionado aos favoritos")
                        selected = emptySet()
                    },
                    Act("Atalho na tela inicial do Android") {
                        if (items.size == 1) {
                            say(Favorites.pinShortcut(context, items.first()))
                        } else {
                            say("Para criar um atalho, selecione um item só")
                        }
                    }
                ),
                onDismiss = { dialog = null }
            )
        }
        is DialogState.ExtractPassword -> PasswordDialog(
            title = "Arquivo protegido",
            message = if (d.wrong) "Senha incorreta. Tente de novo." else "Digite a senha para extrair \"${d.file.name}\".",
            onDismiss = { dialog = null },
            onConfirm = { pwd ->
                dialog = null
                extractArchive(d.file, d.dest, pwd)
            }
        )
        is DialogState.InstallApk -> InstallApkDialog(
            file = d.file,
            onDismiss = { dialog = null },
            onViewContent = { f ->
                dialog = null
                onViewArchive(f)
            },
            onResult = { msg -> say(msg) }
        )
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
    folderSize: Long? = null,
    entry: FileEntry? = null
) {
    val context = LocalContext.current
    // Sem os dados prontos, lê uma vez e guarda (não consulta o armazenamento a cada recomposição)
    val fallback = remember(file.absolutePath, entry) { if (entry == null) FileOps.entryOf(file) else null }
    val info = entry ?: fallback ?: FileEntry(file, false, 0L, 0L)
    val date = remember(info.modified) { rowDateFormat.format(Date(info.modified)) }
    val subtitle = buildString {
        if (!info.isDir) {
            append(Formatter.formatFileSize(context, info.size))
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
        FileIcon(file, 44.dp, entry = info)
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

/** Agrupa por tipo para "Mesmo tipo": pasta, uma das categorias ou "outros". */
private fun typeKey(file: File): String =
    if (file.isDirectory) "dir" else (categoryOf(file)?.name ?: "other")

// Um único formatador de data para todas as linhas (criar um por linha era caro)
private val rowDateFormat: DateFormat = DateFormat.getDateTimeInstance()
