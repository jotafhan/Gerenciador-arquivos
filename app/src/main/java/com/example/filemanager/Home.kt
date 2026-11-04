package com.example.filemanager

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Guarda a última varredura para a tela inicial abrir rápido (vale por 5 minutos). */
private object HomeCache {
    var report: SpaceReport? = null
    var time: Long = 0L
}

private class HomeItem(val icon: String, val label: String, val color: Color, val onClick: () -> Unit)

private fun humanSize(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

internal fun colorFor(cat: Category): Color = when (cat) {
    Category.IMAGES -> Color(0xFF00838F)
    Category.VIDEOS -> Color(0xFFC62828)
    Category.AUDIO -> Color(0xFFF57C00)
    Category.DOCS -> Color(0xFF3F6FD9)
    Category.ARCHIVES -> Color(0xFF6D4C41)
    Category.APKS -> Color(0xFF2E9E5B)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(storage: File, onOpenFolder: (File) -> Unit, onOpenTool: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var refresh by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(HomeCache.report) }
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var recentExpanded by remember { mutableStateOf(prefs.getBoolean("recent_expanded", true)) }

    // Varredura em segundo plano: tamanhos por tipo (menu lateral) e arquivos recentes
    LaunchedEffect(refresh) {
        val fresh = System.currentTimeMillis() - HomeCache.time < 5 * 60_000L
        if (refresh > 0 || !fresh || HomeCache.report == null) {
            val r = withContext(Dispatchers.IO) { Scanner.analyzeSpace(storage) }
            HomeCache.report = r
            HomeCache.time = System.currentTimeMillis()
            report = r
        }
    }

    val (total, free) = remember(refresh) {
        runCatching {
            val stat = StatFs(storage.path)
            stat.totalBytes to stat.availableBytes
        }.getOrDefault(0L to 0L)
    }
    val (ramTotal, ramFree) = remember(refresh) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem to info.availMem
    }

    fun go(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val tools = listOf(
        HomeItem("📊", "Espaço", Color(0xFF1E88E5)) { onOpenTool(TOOL_SPACE) },
        HomeItem("👯", "Duplicados", Color(0xFF43A047)) { onOpenTool(TOOL_DUPLICATES) },
        HomeItem("🗑️", "Lixeira", Color(0xFF757575)) { onOpenTool(TOOL_TRASH) }
    )
    val mediaItems = Category.entries.filter { it != Category.APKS }.map { cat ->
        HomeItem(cat.icon, cat.label, colorFor(cat)) { onOpenTool(cat.name) }
    }
    val mediaAll = mediaItems + HomeItem("⭐", "Favoritos", Color(0xFF1E88E5)) { onOpenTool(TOOL_FAVORITES) }
    val appItems = listOf(
        HomeItem("📲", "Aplicativos", Color(0xFF5C6BC0)) { onOpenTool(TOOL_APPS) },
        HomeItem(Category.APKS.icon, Category.APKS.label, colorFor(Category.APKS)) { onOpenTool(Category.APKS.name) }
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Gerenciador",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp)
                    )
                    DrawerSection("ARMAZENAMENTOS")
                    DrawerEntry("🏠", "Início", null, true) { go {} }
                    DrawerEntry("📱", "Armazenamento interno", humanSize(context, total), false) {
                        go { onOpenFolder(storage) }
                    }
                    DrawerSection("BIBLIOTECA")
                    DrawerEntry("⭐", "Favoritos", null, false) { go { onOpenTool(TOOL_FAVORITES) } }
                    Category.entries.filter { it != Category.APKS }.forEach { cat ->
                        val bytes = report?.byCategory?.firstOrNull { it.first == cat.label }?.second
                        DrawerEntry(cat.icon, cat.label, bytes?.let { humanSize(context, it) }, false) {
                            go { onOpenTool(cat.name) }
                        }
                    }
                    DrawerEntry("🗑️", "Lixeira", null, false) { go { onOpenTool(TOOL_TRASH) } }
                    DrawerSection("APPS")
                    DrawerEntry("📲", "Aplicativos instalados", null, false) { go { onOpenTool(TOOL_APPS) } }
                    DrawerEntry(
                        "🤖",
                        "APKs",
                        report?.byCategory?.firstOrNull { it.first == Category.APKS.label }?.second
                            ?.let { humanSize(context, it) },
                        false
                    ) { go { onOpenTool(Category.APKS.name) } }
                    DrawerSection("FERRAMENTAS")
                    DrawerEntry("📊", "Analisar espaço", null, false) { go { onOpenTool(TOOL_SPACE) } }
                    DrawerEntry("👯", "Achar duplicados", null, false) { go { onOpenTool(TOOL_DUPLICATES) } }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Início") },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰") }
                    },
                    actions = {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Atualizar") },
                                    onClick = {
                                        menuOpen = false
                                        refresh++
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    val used = (total - free).coerceAtLeast(0L)
                    InfoCard(
                        icon = "📱",
                        title = "Armazenamento interno",
                        subtitle = "${humanSize(context, free)} livre",
                        fraction = if (total > 0) used.toFloat() / total else 0f,
                        trailing = "📊",
                        onClick = { onOpenFolder(storage) },
                        onTrailingClick = { onOpenTool(TOOL_SPACE) }
                    )
                }
                item {
                    val used = (ramTotal - ramFree).coerceAtLeast(0L)
                    InfoCard(
                        icon = "🧠",
                        title = "Memória",
                        subtitle = "${humanSize(context, ramFree)} livre",
                        fraction = if (ramTotal > 0) used.toFloat() / ramTotal else 0f,
                        trailing = null,
                        onClick = null,
                        onTrailingClick = null
                    )
                }
                item { SectionHeader("FERRAMENTAS") }
                item { ButtonGrid(tools) }
                item { SectionHeader("MÍDIA") }
                item { ButtonGrid(mediaAll) }
                item { SectionHeader("APPS") }
                item { ButtonGrid(appItems) }
                item {
                    CollapsibleHeader("RECENTE", recentExpanded) {
                        recentExpanded = !recentExpanded
                        prefs.edit().putBoolean("recent_expanded", recentExpanded).apply()
                    }
                }

                val recent = report?.recentFiles
                if (!recentExpanded) {
                    // minimizado: não mostra a lista
                } else if (recent == null) {
                    item { Text("Carregando...", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                } else if (recent.isEmpty()) {
                    item { Text("Nada por aqui ainda", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                } else {
                    items(recent, key = { it.absolutePath }) { f ->
                        FileRow(
                            file = f,
                            extra = f.parent?.removePrefix(storage.absolutePath),
                            selectionMode = false,
                            isSelected = false,
                            onClick = { FileOpener.open(context, f) },
                            onLongClick = {}
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: String,
    title: String,
    subtitle: String,
    fraction: Float,
    trailing: String?,
    onClick: (() -> Unit)?,
    onTrailingClick: (() -> Unit)?
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(icon, style = MaterialTheme.typography.headlineSmall)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Text(subtitle, style = MaterialTheme.typography.bodyLarge)
            }
            if (trailing != null) {
                TextButton(onClick = { onTrailingClick?.invoke() }) {
                    Text(trailing, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonGrid(items: List<HomeItem>) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { RoundButton(it) }
    }
}

@Composable
private fun RoundButton(item: HomeItem) {
    Column(
        modifier = Modifier
            .width(66.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = item.onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(item.color),
            contentAlignment = Alignment.Center
        ) {
            Text(item.icon, fontSize = 24.sp)
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun DrawerEntry(icon: String, label: String, badge: String?, selected: Boolean, onClick: () -> Unit) {
    if (badge != null) {
        NavigationDrawerItem(
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            selected = selected,
            onClick = onClick,
            icon = { Text(icon) },
            badge = { Text(badge, style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    } else {
        NavigationDrawerItem(
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            selected = selected,
            onClick = onClick,
            icon = { Text(icon) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
}

@Composable
private fun CollapsibleHeader(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (expanded) "▲" else "▼",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
