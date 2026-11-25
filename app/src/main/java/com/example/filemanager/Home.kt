package com.example.filemanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Guarda a última varredura para a tela inicial abrir rápido (vale por 5 minutos). */
private object HomeCache {
    var report: SpaceReport? = null
    var time: Long = 0L
    var appsBytes: Long? = null
    var downloadBytes: Long? = null
}

internal fun colorFor(cat: Category): Color = when (cat) {
    Category.IMAGES -> Color(0xFF00838F)
    Category.VIDEOS -> Color(0xFFC62828)
    Category.AUDIO -> Color(0xFFF57C00)
    Category.DOCS -> Color(0xFF3F6FD9)
    Category.ARCHIVES -> Color(0xFF6D4C41)
    Category.APKS -> Color(0xFF2E9E5B)
}

// Paleta da tela inicial
private val BLUE_CARD = Color(0xFF0D63B5)
private val LIME = Color(0xFFC8F169)
private val TILE_VIDEO = Color(0xFF8EC5FF)
private val TILE_ARCHIVE = Color(0xFFFFB067)
private val TILE_AUDIO = Color(0xFFFF9EC4)
private val TILE_APPS = Color(0xFFC5A8FF)
private val TILE_PHOTO = Color(0xFF7FE0C3)
private val TILE_DOWNLOAD = Color(0xFFFFE07A)
private val TILE_TEXT = Color(0xFF111111)

private class HomePalette(val bg: Color, val card: Color, val text: Color, val sub: Color)

private val DarkPalette = HomePalette(Color(0xFF0A0A0A), Color(0xFF1A1A1A), Color.White, Color(0xFFB0B0B0))
private val LightPalette = HomePalette(Color(0xFFF3F3F3), Color(0xFFFFFFFF), Color(0xFF111111), Color(0xFF666666))

private class HomeTile(val label: String, val icon: String, val color: Color, val bytes: Long?, val onClick: () -> Unit)

private fun humanSize(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

/** Tamanho no estilo da tela: "174 GB", "28,97 GB", "580 MB". */
private fun sizeText(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return when {
        gb >= 100 -> String.format(Locale.getDefault(), "%.0f GB", gb)
        gb >= 1 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    }
}

/** "hoje", "ontem" ou "há N dias". */
private fun relativeDay(millis: Long): String {
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(millis)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "hoje"
        days == 1 -> "ontem"
        else -> "há $days dias"
    }
}

/** Pastas abertas recentemente (mais recente primeiro), guardadas em SharedPreferences. */
object RecentFolders {
    private const val KEY = "recent_folders"
    private const val MAX = 20

    private fun prefs(context: Context) = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun add(context: Context, path: String) {
        val old = prefs(context).getString(KEY, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() && it.substringAfter('\t') != path }
        val updated = (listOf("${System.currentTimeMillis()}\t$path") + old).take(MAX)
        prefs(context).edit().putString(KEY, updated.joinToString("\n")).apply()
    }

    fun list(context: Context, limit: Int): List<Pair<File, Long>> =
        prefs(context).getString(KEY, "").orEmpty()
            .split('\n')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val millis = line.substringBefore('\t').toLongOrNull()
                val file = File(line.substringAfter('\t'))
                if (millis != null && file.isDirectory) file to millis else null
            }
            .take(limit)
            .toList()
}

@Suppress("DEPRECATION")
private fun userAppsBytes(context: Context): Long {
    val pm = context.packageManager
    return pm.getInstalledApplications(0)
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .sumOf { ai ->
            File(ai.sourceDir).length() + (ai.splitSourceDirs?.sumOf { File(it).length() } ?: 0L)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(storage: File, onOpenFolder: (File) -> Unit, onOpenTool: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

    var report by remember { mutableStateOf(HomeCache.report) }
    var appsBytes by remember { mutableStateOf(HomeCache.appsBytes) }
    var downloadBytes by remember { mutableStateOf(HomeCache.downloadBytes) }

    // Tamanhos por tipo (varredura guardada por 5 minutos)
    LaunchedEffect(Unit) {
        val fresh = System.currentTimeMillis() - HomeCache.time < 5 * 60_000L
        if (!fresh || HomeCache.report == null) {
            val r = withContext(Dispatchers.IO) { Scanner.analyzeSpace(storage) }
            HomeCache.report = r
            HomeCache.time = System.currentTimeMillis()
            report = r
        }
    }
    // Tamanho dos apps instalados e da pasta Download
    LaunchedEffect("extras") {
        val apps = withContext(Dispatchers.IO) { userAppsBytes(context) }
        HomeCache.appsBytes = apps
        appsBytes = apps
        val download = withContext(Dispatchers.IO) {
            File(storage, "Download").takeIf { it.isDirectory }?.let { Scanner.measure(listOf(it)).bytes }
        }
        HomeCache.downloadBytes = download
        downloadBytes = download
    }

    val (total, free) = remember {
        runCatching {
            val stat = StatFs(storage.path)
            stat.totalBytes to stat.availableBytes
        }.getOrDefault(0L to 0L)
    }
    val used = (total - free).coerceAtLeast(0L)
    val recent = remember { RecentFolders.list(context, 5) }

    fun catBytes(cat: Category): Long? = report?.byCategory?.firstOrNull { it.first == cat.label }?.second

    fun go(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val downloadDir = File(storage, "Download")
    val tilesTop = listOf(
        HomeTile("Vídeos", "🎬", TILE_VIDEO, catBytes(Category.VIDEOS)) { onOpenTool(Category.VIDEOS.name) },
        HomeTile("Compactados", "🗜️", TILE_ARCHIVE, catBytes(Category.ARCHIVES)) { onOpenTool(Category.ARCHIVES.name) },
        HomeTile("Áudio", "🎵", TILE_AUDIO, catBytes(Category.AUDIO)) { onOpenTool(Category.AUDIO.name) }
    )
    val tilesBottom = listOf(
        HomeTile("Apps", "📲", TILE_APPS, appsBytes) { onOpenTool(TOOL_APPS) },
        HomeTile("Fotos", "🖼️", TILE_PHOTO, catBytes(Category.IMAGES)) { onOpenTool(Category.IMAGES.name) },
        HomeTile("Download", "⬇️", TILE_DOWNLOAD, downloadBytes) {
            onOpenFolder(if (downloadDir.isDirectory) downloadDir else storage)
        }
    )

    // Barra colorida: um trecho por tipo, o resto usado em branco e o livre em preto
    val known = listOf(
        catBytes(Category.VIDEOS) to TILE_VIDEO,
        catBytes(Category.ARCHIVES) to TILE_ARCHIVE,
        catBytes(Category.AUDIO) to TILE_AUDIO,
        catBytes(Category.IMAGES) to TILE_PHOTO,
        appsBytes to TILE_APPS
    )
    val knownSum = known.sumOf { it.first ?: 0L }
    val segments: List<Pair<Float, Color>> = if (total > 0) {
        known.map { ((it.first ?: 0L).toFloat() / total) to it.second } +
            listOf(
                ((used - knownSum).coerceAtLeast(0L).toFloat() / total) to Color.White,
                (free.toFloat() / total) to Color.Black
            )
    } else {
        emptyList()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Ark Gerenciador",
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
                        DrawerEntry(cat.icon, cat.label, catBytes(cat)?.let { humanSize(context, it) }, false) {
                            go { onOpenTool(cat.name) }
                        }
                    }
                    DrawerEntry("🗑️", "Lixeira", null, false) { go { onOpenTool(TOOL_TRASH) } }
                    DrawerSection("APPS")
                    DrawerEntry("📲", "Aplicativos instalados", null, false) { go { onOpenTool(TOOL_APPS) } }
                    DrawerEntry("🤖", "APKs", catBytes(Category.APKS)?.let { humanSize(context, it) }, false) {
                        go { onOpenTool(Category.APKS.name) }
                    }
                    DrawerSection("FERRAMENTAS")
                    DrawerEntry("📊", "Analisar armazenamento", null, false) { go { onOpenTool(TOOL_SPACE) } }
                    DrawerEntry("👯", "Achar duplicados", null, false) { go { onOpenTool(TOOL_DUPLICATES) } }
                    DrawerEntry("⚙️", "Configurações", null, false) { go { onOpenTool(TOOL_SETTINGS) } }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            containerColor = palette.bg,
            bottomBar = {
                HomeNav(
                    palette = palette,
                    onFolders = { onOpenFolder(storage) },
                    onDuplicates = { onOpenTool(TOOL_DUPLICATES) },
                    onFavorites = { onOpenTool(TOOL_FAVORITES) }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            "Ark Gerenciador",
                            color = palette.text,
                            fontSize = 34.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", color = palette.text, fontSize = 22.sp)
                        }
                    }
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(32.dp))
                            .background(BLUE_CARD)
                            .clickable { onOpenFolder(storage) }
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    ) {
                        Text("Armazenamento interno", color = TILE_TEXT, fontSize = 17.sp)
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
                            Text(sizeText(free), color = TILE_TEXT, fontSize = 52.sp, fontWeight = FontWeight.Medium)
                            Text(
                                " livres",
                                color = TILE_TEXT,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
                            )
                        }
                        StorageBar(segments)
                        Text(
                            "${sizeText(used)} usados de ${sizeText(total)}",
                            color = TILE_TEXT,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(palette.card)
                            .clickable { onOpenTool(TOOL_SPACE) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(LIME),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📊", fontSize = 26.sp)
                        }
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                "ANALISAR ARMAZENAMENTO",
                                color = palette.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Tipos, maiores pastas e arquivos",
                                color = palette.sub,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
                item { TileRow(tilesTop) }
                item { TileRow(tilesBottom) }
                item {
                    Text(
                        "PASTAS RECENTES",
                        color = palette.sub,
                        fontSize = 15.sp,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                    )
                }
                if (recent.isEmpty()) {
                    item {
                        Text(
                            "As pastas que você abrir aparecem aqui.",
                            color = palette.sub,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                } else {
                    items(recent.size) { index ->
                        val (folder, millis) = recent[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onOpenFolder(folder) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(palette.card),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📁", fontSize = 24.sp)
                            }
                            Text(
                                folder.name.ifEmpty { "/" },
                                color = palette.text,
                                fontSize = 22.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 16.dp)
                            )
                            Text(relativeDay(millis), color = palette.sub, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageBar(segments: List<Pair<Float, Color>>) {
    Row(modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        segments.filter { it.first > 0.002f }.forEachIndexed { index, (fraction, color) ->
            if (index > 0) Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
            Box(Modifier.weight(fraction).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun TileRow(tiles: List<HomeTile>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { tile ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(116.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(tile.color)
                    .clickable(onClick = tile.onClick)
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(tile.icon, fontSize = 20.sp)
                Column {
                    Text(
                        tile.bytes?.let { sizeText(it) } ?: "—",
                        color = TILE_TEXT,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(tile.label, color = TILE_TEXT, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HomeNav(palette: HomePalette, onFolders: () -> Unit, onDuplicates: () -> Unit, onFavorites: () -> Unit) {
    Box(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(palette.card)
                .padding(vertical = 14.dp)
        ) {
            NavItem("📁", "Pastas", palette, onFolders, Modifier.weight(1f))
            NavItem("📑", "Duplicados", palette, onDuplicates, Modifier.weight(1f))
            NavItem("⭐", "Favoritos", palette, onFavorites, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(icon: String, label: String, palette: HomePalette, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 24.sp)
        Text(label, color = palette.text, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
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
