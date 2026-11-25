package com.example.filemanager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private data class AppInfo(
    val label: String,
    val pkg: String,
    val version: String,
    val apkSize: Long,
    val installed: Long,
    val updated: Long,
    val system: Boolean,
    val sourceDir: String,
    val splitDirs: List<String>
)

private fun loadApps(pm: PackageManager): List<AppInfo> {
    @Suppress("DEPRECATION")
    val packages = pm.getInstalledPackages(0)
    return packages.mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        val flags = ai.flags
        // "Sistema" = app de fábrica que não recebeu atualização
        val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        val source = ai.sourceDir ?: ""
        AppInfo(
            label = pm.getApplicationLabel(ai).toString(),
            pkg = pi.packageName,
            version = pi.versionName ?: "",
            apkSize = runCatching { File(source).length() }.getOrDefault(0L),
            installed = pi.firstInstallTime,
            updated = pi.lastUpdateTime,
            system = system,
            sourceDir = source,
            splitDirs = ai.splitSourceDirs?.toList() ?: emptyList()
        )
    }
}

private fun safeName(text: String): String = text.replace(Regex("[^A-Za-z0-9._-]+"), "_")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppsScreen(storage: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(SortKey.NAME.name) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<AppInfo?>(null) }

    val listState = rememberLazyListState()
    LaunchedEffect(sortName, ascending, tab) { listState.scrollToItem(0) }

    LaunchedEffect(refreshKey) {
        apps = withContext(Dispatchers.IO) { loadApps(pm) }
    }

    // Ao voltar de uma desinstalação (ou de qualquer outra tela), recarrega a lista
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        var skipFirst = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipFirst) {
                    skipFirst = false
                } else {
                    refreshKey++
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    BackHandler { onClose() }

    val sortKey = SortKey.valueOf(sortName)
    val shown = remember(apps, tab, query, sortName, ascending) {
        val byKey: Comparator<AppInfo> = when (sortKey) {
            SortKey.NAME -> compareBy<AppInfo> { it.label.lowercase() }
            SortKey.DATE -> compareBy<AppInfo> { it.updated }
            SortKey.SIZE -> compareBy<AppInfo> { it.apkSize }
        }
        apps.orEmpty()
            .filter { it.system == (tab == 1) }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
            .sortedWith(if (ascending) byKey else byKey.reversed())
    }
    val userCount = apps?.count { !it.system } ?: 0
    val systemCount = apps?.count { it.system } ?: 0

    fun say(msg: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    /** Copia o(s) APK(s) do app instalado para a pasta APKs_salvos. */
    fun saveApk(app: AppInfo) {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(storage, "APKs_salvos")
                    dir.mkdirs()
                    val base = safeName(app.label.ifBlank { app.pkg }) + "_" + safeName(app.version)
                    if (app.splitDirs.isEmpty()) {
                        val target = FileOps.uniqueTarget(dir, "$base.apk")
                        File(app.sourceDir).copyTo(target)
                        "APK salvo em APKs_salvos/${target.name}"
                    } else {
                        val folder = FileOps.uniqueTarget(dir, base)
                        folder.mkdirs()
                        File(app.sourceDir).copyTo(File(folder, "base.apk"))
                        app.splitDirs.forEach { File(it).copyTo(File(folder, File(it).name)) }
                        "App com várias partes (split): salvo na pasta APKs_salvos/${folder.name}"
                    }
                }.getOrElse { "Erro ao salvar: ${it.message}" }
            }
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Aplicativos") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            SortKey.entries.forEach { key ->
                                val arrow = if (key == sortKey) {
                                    if (ascending) " ↑" else " ↓"
                                } else ""
                                val name = if (key == SortKey.DATE) "Última atualização" else key.label
                                DropdownMenuItem(
                                    text = { Text("Ordenar por $name$arrow") },
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
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Usuário ($userCount)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sistema ($systemCount)") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Buscar aplicativo") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            when {
                apps == null -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Lendo aplicativos...", modifier = Modifier.padding(16.dp))
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum aplicativo encontrado")
                }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.pkg }) { app ->
                        AppRow(app = app, pm = pm, onClick = { picked = app })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    picked?.let { app ->
        val fmtDate = { millis: Long -> DateFormat.getDateTimeInstance().format(Date(millis)) }
        val info = buildString {
            append(app.pkg)
            append("\nVersão: ").append(app.version.ifBlank { "?" })
            append("\nAPK: ").append(Formatter.formatFileSize(context, app.apkSize))
            if (app.splitDirs.isNotEmpty()) append(" (+ ${app.splitDirs.size} partes)")
            append("\nInstalado: ").append(fmtDate(app.installed))
            append("\nAtualizado: ").append(fmtDate(app.updated))
        }
        val actions = buildList<Act> {
            add(Act("Abrir") {
                val intent = pm.getLaunchIntentForPackage(app.pkg)
                if (intent != null) {
                    runCatching { context.startActivity(intent) }
                } else {
                    say("Este app não tem tela para abrir")
                }
            })
            add(Act("Informações do app") {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    )
                }
            })
            add(Act("Salvar APK") { saveApk(app) })
            if (!app.system) {
                add(Act("Desinstalar") {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    }
                })
            }
        }
        ActionsDialog(title = app.label, info = info, actions = actions, onDismiss = { picked = null })
    }
}

@Composable
private fun AppRow(app: AppInfo, pm: PackageManager, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Box(Modifier.size(40.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.pkg,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(Formatter.formatFileSize(context, app.apkSize), style = MaterialTheme.typography.labelMedium)
    }
}
