package com.example.filemanager

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class AnalysisStep(val label: String) {
    TYPES("Resumo por tipo"),
    BIG("Arquivos grandes"),
    RECENT("Arquivos recentes"),
    EMPTY("Pastas vazias"),
    RESIDUAL("Temporários e residuais"),
    APKS("APKs já instalados")
}

private enum class ReviewKind(val title: String, val preselect: Boolean, val folders: Boolean) {
    BIG("Arquivos grandes", false, false),
    RECENT("Arquivos recentes", false, false),
    EMPTY("Pastas vazias", true, true),
    RESIDUAL("Temporários e residuais", false, false),
    APKS("APKs já instalados", false, false)
}

private fun itemsOf(r: AnalysisResult, kind: ReviewKind): List<ScanItem> = when (kind) {
    ReviewKind.BIG -> r.bigFiles
    ReviewKind.RECENT -> r.recentFiles
    ReviewKind.EMPTY -> r.emptyFolders
    ReviewKind.RESIDUAL -> r.residualFiles
    ReviewKind.APKS -> r.installedApks
}

/** "Analisar armazenamento": varredura animada e, depois, cartões com o que dá para limpar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpaceScreen(
    storage: File,
    onClose: () -> Unit,
    onShowInFolder: (File) -> Unit,
    onOpenDuplicates: () -> Unit
) {
    val context = LocalContext.current
    val target = remember { AnalysisTarget.take()?.let { File(it) }?.takeIf { it.isDirectory } ?: storage }
    var runKey by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<AnalysisResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var doneSteps by remember { mutableIntStateOf(0) }
    var reviewing by remember { mutableStateOf<ReviewKind?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val progress = remember(runKey) { ScanProgress() }

    LaunchedEffect(runKey) {
        result = null
        failure = null
        doneSteps = 0
        val outcome = withContext(Dispatchers.IO) { runCatching { Analyzer.analyze(target, context, progress) } }
        val r = outcome.getOrNull()
        if (r == null) {
            failure = outcome.exceptionOrNull()?.message ?: "Não foi possível analisar esta pasta"
            return@LaunchedEffect
        }
        // Mostra cada etapa sendo concluída antes de abrir os resultados
        for (i in 1..AnalysisStep.entries.size) {
            doneSteps = i
            delay(150)
        }
        result = r
    }
    LaunchedEffect(runKey, result) {
        while (result == null) {
            delay(150)
            tick++
        }
    }

    BackHandler {
        if (reviewing != null) reviewing = null else onClose()
    }

    val r = result
    val review = reviewing
    if (r != null && review != null) {
        ReviewScreen(
            title = review.title,
            entries = itemsOf(r, review),
            isFolders = review.folders,
            preselect = review.preselect,
            storage = storage,
            onClose = { reviewing = null },
            onDeleted = { deleted ->
                result = r.copy(
                    bigFiles = r.bigFiles.filter { it.file.absolutePath !in deleted },
                    recentFiles = r.recentFiles.filter { it.file.absolutePath !in deleted },
                    emptyFolders = r.emptyFolders.filter { it.file.absolutePath !in deleted },
                    residualFiles = r.residualFiles.filter { it.file.absolutePath !in deleted },
                    installedApks = r.installedApks.filter { it.file.absolutePath !in deleted }
                )
                reviewing = null
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (target == storage) "Analisar armazenamento" else "Analisar: ${target.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                    actions = {
                        if (r != null || failure != null) {
                            TextButton(onClick = { runKey++ }) { Text("🔄") }
                        }
                    }
                )
            }
        ) { padding ->
            val modifier = Modifier.padding(padding).fillMaxSize()
            when {
                failure != null -> Box(modifier, contentAlignment = Alignment.Center) {
                    Text(failure ?: "", textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
                }
                r == null -> ScanningContent(target, progress, tick, doneSteps, modifier)
                else -> ResultsContent(
                    r = r,
                    modifier = modifier,
                    onReview = { reviewing = it },
                    onItem = { item -> onShowInFolder(if (item.file.isDirectory) item.file else item.file.parentFile ?: item.file) },
                    onOpenDuplicates = onOpenDuplicates
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Varredura animada

@Composable
private fun ScanRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val outer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "outer"
    )
    val inner by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "inner"
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.outlineVariant

    Box(modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawCircle(color = track, radius = diameter / 2f, style = Stroke(width = 2.dp.toPx()))
            rotate(outer) {
                for (i in 0 until 6) {
                    drawArc(
                        color = primary,
                        startAngle = i * 60f + 4f,
                        sweepAngle = 40f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            val inside = diameter - 56.dp.toPx()
            val insideTopLeft = Offset((size.width - inside) / 2f, (size.height - inside) / 2f)
            rotate(inner) {
                drawArc(
                    color = tertiary,
                    startAngle = 0f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = insideTopLeft,
                    size = Size(inside, inside),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = tertiary.copy(alpha = 0.45f),
                    startAngle = 180f,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = insideTopLeft,
                    size = Size(inside, inside),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text("📊", fontSize = 36.sp)
        }
    }
}

@Composable
private fun ScanningContent(target: File, progress: ScanProgress, tick: Int, doneSteps: Int, modifier: Modifier) {
    val context = LocalContext.current
    val refresh = tick // ler "tick" atualiza os contadores a cada 150 ms
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        ScanRing()
        Spacer(Modifier.height(16.dp))
        Text(
            target.absolutePath,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Text(
            "${progress.files} arquivos  •  ${progress.folders} pastas  •  " +
                Formatter.formatFileSize(context, progress.bytes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
        )
        AnalysisStep.entries.forEachIndexed { index, step ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(step.label, modifier = Modifier.weight(1f))
                    if (index < doneSteps) {
                        Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Resultados

@Composable
private fun ResultsContent(
    r: AnalysisResult,
    modifier: Modifier,
    onReview: (ReviewKind) -> Unit,
    onItem: (ScanItem) -> Unit,
    onOpenDuplicates: () -> Unit
) {
    val context = LocalContext.current
    val recoverable = r.residualFiles.sumOf { it.size } + r.installedApks.sumOf { it.size }
    val rootPath = r.root.absolutePath

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Espaço que dá para liberar", style = MaterialTheme.typography.labelLarge)
                    Text(
                        Formatter.formatFileSize(context, recoverable),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "em arquivos temporários, residuais e APKs já instalados",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (r.totalBytes > 0) {
                        val used = (r.totalBytes - r.freeBytes).coerceAtLeast(0L)
                        LinearProgressIndicator(
                            progress = { used.toFloat() / r.totalBytes },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
                        )
                        Text(
                            "Armazenamento: ${Formatter.formatFileSize(context, used)} usados de " +
                                "${Formatter.formatFileSize(context, r.totalBytes)}  •  " +
                                "${Formatter.formatFileSize(context, r.freeBytes)} livres",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        "${r.fileCount} arquivos analisados (${Formatter.formatFileSize(context, r.scannedBytes)})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            CardShell(title = "Por tipo de arquivo", total = Formatter.formatFileSize(context, r.scannedBytes)) {
                r.byCategory.take(6).forEach { (label, bytes) ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(label, modifier = Modifier.weight(1f))
                            Text(Formatter.formatFileSize(context, bytes), style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(
                            progress = { if (r.scannedBytes > 0) bytes.toFloat() / r.scannedBytes else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            ReviewCard("Arquivos grandes", ReviewKind.BIG, r.bigFiles, rootPath, onReview, onItem)
        }
        item {
            ReviewCard("Arquivos recentes", ReviewKind.RECENT, r.recentFiles, rootPath, onReview, onItem)
        }
        item {
            ReviewCard("Pastas vazias", ReviewKind.EMPTY, r.emptyFolders, rootPath, onReview, onItem)
        }
        item {
            ReviewCard("Temporários e residuais", ReviewKind.RESIDUAL, r.residualFiles, rootPath, onReview, onItem)
        }
        item {
            ReviewCard("APKs já instalados", ReviewKind.APKS, r.installedApks, rootPath, onReview, onItem)
        }
        item {
            CardShell(title = "Arquivos duplicados", total = "") {
                Text(
                    "Procura arquivos idênticos (mesmo conteúdo) e deixa você apagar as cópias.",
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(
                    onClick = onOpenDuplicates,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) { Text("Procurar duplicados") }
            }
        }
    }
}

@Composable
private fun CardShell(title: String, total: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (total.isNotEmpty()) {
                    Text(
                        total,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ReviewCard(
    title: String,
    kind: ReviewKind,
    entries: List<ScanItem>,
    rootPath: String,
    onReview: (ReviewKind) -> Unit,
    onItem: (ScanItem) -> Unit
) {
    val context = LocalContext.current
    val total = if (kind.folders) {
        "${entries.size} pasta(s)"
    } else {
        Formatter.formatFileSize(context, entries.sumOf { it.size })
    }
    CardShell(title = title, total = if (entries.isEmpty()) "" else total) {
        if (entries.isEmpty()) {
            Text(
                "Nada encontrado",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            entries.take(3).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onItem(item) }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileIcon(item.file, 40.dp, entry = FileEntry(item.file, kind.folders, item.size, item.modified))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.file.parent?.removePrefix(rootPath).orEmpty().ifEmpty { "/" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!kind.folders) {
                        Text(Formatter.formatFileSize(context, item.size), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (entries.size > 3) {
                Text(
                    "e mais ${entries.size - 3}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            FilledTonalButton(
                onClick = { onReview(kind) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) { Text("Revisar e apagar") }
        }
    }
}

// ---------------------------------------------------------------- Revisar e apagar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(
    title: String,
    entries: List<ScanItem>,
    isFolders: Boolean,
    preselect: Boolean,
    storage: File,
    onClose: () -> Unit,
    onDeleted: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember {
        mutableStateOf(if (preselect) entries.map { it.file.absolutePath }.toSet() else emptySet())
    }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val chosen = entries.filter { it.file.absolutePath in selected }
    val chosenBytes = chosen.sumOf { it.size }
    val allSelected = entries.isNotEmpty() && selected.size == entries.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
                actions = {
                    TextButton(
                        onClick = {
                            selected = if (allSelected) emptySet() else entries.map { it.file.absolutePath }.toSet()
                        }
                    ) { Text(if (allSelected) "Nenhum" else "Todos") }
                }
            )
        },
        bottomBar = {
            CompactBottomBar {
                Text(
                    if (isFolders) "${chosen.size} selecionada(s)"
                    else "${chosen.size} item(ns)  •  ${Formatter.formatFileSize(context, chosenBytes)}",
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                Button(enabled = chosen.isNotEmpty() && !busy, onClick = { confirm = true }) { Text("Apagar") }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.file.absolutePath }) { item ->
                    val path = item.file.absolutePath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (path in selected) selected - path else selected + path }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = path in selected, onCheckedChange = null)
                        FileIcon(item.file, 40.dp, entry = FileEntry(item.file, isFolders, item.size, item.modified))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                item.file.parent?.removePrefix(storage.absolutePath).orEmpty().ifEmpty { "/" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isFolders) {
                            Text(Formatter.formatFileSize(context, item.size), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirm) {
        DeleteDialog(
            files = chosen.map { it.file },
            allInTrash = isFolders, // pastas vazias são removidas direto (não há o que guardar)
            onDismiss = { confirm = false },
            onConfirm = { toTrash ->
                confirm = false
                busy = true
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        val ok = HashSet<String>()
                        for (item in chosen) {
                            val f = item.file
                            val success = when {
                                isFolders -> f.delete() // só apaga se continuar vazia
                                toTrash -> Trash.moveToTrash(storage, f).isSuccess
                                else -> f.delete()
                            }
                            if (success) ok.add(f.absolutePath)
                        }
                        ok
                    }
                    busy = false
                    Toast.makeText(context, "${deleted.size} item(ns) removido(s)", Toast.LENGTH_SHORT).show()
                    onDeleted(deleted)
                }
            }
        )
    }
}
