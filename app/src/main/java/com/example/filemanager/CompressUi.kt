package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ Excluir

/** Confirmação de exclusão: mostra o que será apagado, o tamanho e a opção da lixeira. */
@Composable
internal fun DeleteDialog(
    files: List<File>,
    allInTrash: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (toTrash: Boolean) -> Unit
) {
    val context = LocalContext.current
    var measure by remember { mutableStateOf<Measure?>(null) }
    var toTrash by remember { mutableStateOf(!allInTrash) }

    LaunchedEffect(files) {
        measure = withContext(Dispatchers.IO) { Scanner.measure(files) }
    }

    val single = files.singleOrNull()
    val what = if (single != null) {
        val kind = if (single.isDirectory) "a pasta" else "o arquivo"
        "$kind \"${single.name}\""
    } else {
        "${files.size} itens"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toTrash) "Mover para a lixeira" else "Excluir definitivamente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (toTrash) "Tem certeza que deseja mover $what para a lixeira?"
                    else "Tem certeza que deseja excluir $what permanentemente? Isso não pode ser desfeito."
                )
                val m = measure
                if (m == null) {
                    Text("Calculando tamanho...")
                } else {
                    val size = Formatter.formatFileSize(context, m.bytes)
                    if (files.any { it.isDirectory }) {
                        Text("Contém: ${m.files} arquivo(s)\nTamanho: $size")
                    } else {
                        Text("Tamanho: $size")
                    }
                }
                if (!allInTrash) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { toTrash = !toTrash }.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = toTrash, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Mover para a lixeira")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(toTrash && !allInTrash) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ------------------------------------------------------------------ Senha

@Composable
internal fun PasswordDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Senha") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) { Text(if (visible) "🙈" else "👁") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ------------------------------------------------------------------ Criação de arquivo

@Composable
private fun <T> DropdownField(
    label: String,
    valueLabel: String,
    options: List<Pair<String, T>>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val color = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { open = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(valueLabel, color = color)
                Text("▾", color = color)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            open = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color)
    }
}

/** Diálogo "Criação de arquivo": nome, destino, formato, nível, senha, volumes e opções. */
@Composable
internal fun CompressDialog(
    files: List<File>,
    root: File,
    initialDest: File,
    onDismiss: () -> Unit,
    onConfirm: (dest: File, name: String, options: CompressOptions) -> Unit
) {
    val defaultName = remember(files) {
        val first = files.first()
        if (files.size == 1) {
            if (first.isDirectory) first.name else first.nameWithoutExtension
        } else {
            "Arquivos"
        }
    }
    var name by remember { mutableStateOf(defaultName) }
    var dest by remember { mutableStateOf(initialDest) }
    var pickDest by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var level by remember { mutableStateOf(LevelOption.NORMAL) }
    var encryption by remember { mutableStateOf(EncryptionOption.NONE) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var split by remember { mutableStateOf(SplitOption.NONE) }
    var deleteAfter by remember { mutableStateOf(false) }
    var separate by remember { mutableStateOf(false) }

    val isZip = format == ArchiveFormat.ZIP
    val canLevel = format != ArchiveFormat.TAR
    val levelOptions = if (format == ArchiveFormat.TAR_BZ2 || format == ArchiveFormat.TAR_XZ) {
        LevelOption.entries.filter { it != LevelOption.STORE } // esses formatos sempre comprimem
    } else {
        LevelOption.entries.toList()
    }
    val effectiveLevel = if (level in levelOptions) level else LevelOption.FAST
    val needsPassword = isZip && encryption != EncryptionOption.NONE
    val valid = name.isNotBlank() && (!needsPassword || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Criação de arquivo") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Arquivo") },
                        suffix = { Text(".${format.ext}") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { pickDest = true }) { Text("…") }
                }
                Text(
                    "Salvar em: ${dest.absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField(
                        label = "Formato do arquivo",
                        valueLabel = format.ext,
                        options = ArchiveFormat.entries.map { it.ext to it },
                        enabled = true,
                        modifier = Modifier.weight(1f)
                    ) { format = it }
                    DropdownField(
                        label = "Nível de compressão",
                        valueLabel = if (canLevel) effectiveLevel.label else "—",
                        options = levelOptions.map { it.label to it },
                        enabled = canLevel,
                        modifier = Modifier.weight(1f)
                    ) { level = it }
                }
                Text(
                    text = when {
                        format == ArchiveFormat.TAR ->
                            "TAR só junta os arquivos, sem comprimir: o tamanho fica igual ao original."
                        effectiveLevel == LevelOption.STORE ->
                            "Sem compressão só junta os arquivos num pacote: o tamanho fica quase igual ao original."
                        else ->
                            "Fotos, vídeos, MP3, APK e ZIP já vêm comprimidos: o tamanho quase não muda."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DropdownField(
                    label = "Criptografia",
                    valueLabel = if (isZip) encryption.label else "Só disponível no ZIP",
                    options = EncryptionOption.entries.map { it.label to it },
                    enabled = isZip
                ) { encryption = it }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    enabled = needsPassword,
                    label = { Text("Senha") },
                    placeholder = { Text("Digite a senha") },
                    visualTransformation =
                        if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }, enabled = needsPassword) {
                            Text(if (showPassword) "🙈" else "👁")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownField(
                    label = "Dividir em volumes",
                    valueLabel = if (isZip) split.label else "Só disponível no ZIP",
                    options = SplitOption.entries.map { it.label to it },
                    enabled = isZip
                ) { split = it }
                CheckRow("Excluir arquivos depois da compactação", deleteAfter, true) { deleteAfter = it }
                CheckRow("Criar arquivo separado", separate, files.size > 1) { separate = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        dest,
                        name.trim(),
                        CompressOptions(
                            format = format,
                            level = effectiveLevel,
                            encryption = if (isZip) encryption else EncryptionOption.NONE,
                            password = if (needsPassword) password else "",
                            splitBytes = if (isZip) split.bytes else null,
                            separate = separate && files.size > 1,
                            deleteAfter = deleteAfter
                        )
                    )
                }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (pickDest) {
        FolderPickerDialog(
            title = "Salvar em...",
            root = root,
            start = dest,
            confirmLabel = "Escolher",
            onDismiss = { pickDest = false },
            onPick = {
                dest = it
                pickDest = false
            }
        )
    }
}

// ------------------------------------------------------------------ Progresso

/** Mostra o andamento de uma operação longa (compactar/extrair) com botão de cancelar. */
@Composable
internal fun ProgressDialog(
    title: String,
    progress: OpProgress,
    onCancel: () -> Unit,
    onHide: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            tick++
        }
    }
    // Ler "tick" aqui faz a tela se atualizar a cada 200 ms
    val refresh = tick
    val total = progress.total
    val done = progress.done
    val label = progress.label

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (label.isNotEmpty()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val percent = ((done * 100) / total).coerceIn(0L, 100L)
                    Text(
                        "$percent%  •  ${Formatter.formatFileSize(context, done)} de " +
                            Formatter.formatFileSize(context, total)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (done > 0) Text(Formatter.formatFileSize(context, done) + " processados")
                }
                val elapsed = progress.elapsedMs()
                val remaining = progress.remainingMs()
                Text(
                    buildString {
                        append("Decorrido: ").append(formatClock(elapsed))
                        if (remaining != null) {
                            append("\nRestante: ~").append(formatClock(remaining))
                            append("\nTotal estimado: ~").append(formatClock(elapsed + remaining))
                        } else if (total > 0) {
                            append("\nCalculando o tempo restante...")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancelar") } },
        dismissButton = {
            if (onHide != null) TextButton(onClick = onHide) { Text("Ocultar") }
        }
    )
}

// ------------------------------------------------------------------ Operação em andamento (global)

/** Formata milissegundos como m:ss (ou h:mm:ss). */
internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** A operação longa em andamento (compactar/extrair), visível em qualquer tela do app. */
object ActiveTask {
    var current by mutableStateOf<Pair<String, OpProgress>?>(null)
    var hidden by mutableStateOf(false)

    // Resultado da última operação (lido pelo serviço para a notificação final)
    @Volatile var lastMessage: String? = null
    @Volatile var lastOk: Boolean = true
    @Volatile var lastElapsedMs: Long = 0L

    fun start(context: Context, title: String, progress: OpProgress) {
        lastMessage = null
        current = title to progress
        hidden = false
        // O serviço mostra o progresso na barra de notificações e mantém o app vivo
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java))
        }
    }

    /**
     * Encerra a operação. [message] é o texto da notificação final (null = sem notificação).
     * Devolve se a janela estava oculta.
     */
    fun finish(message: String?, ok: Boolean): Boolean {
        val wasHidden = hidden
        val started = current?.second?.startedAt
        lastElapsedMs = if (started != null) System.currentTimeMillis() - started else 0L
        lastMessage = message
        lastOk = ok
        current = null
        hidden = false
        return wasHidden
    }
}

/** Faixa compacta mostrada quando a janela de progresso está oculta. Tocar reabre a janela. */
@Composable
internal fun ProgressBanner(title: String, progress: OpProgress, onShow: () -> Unit, onCancel: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            tick++
        }
    }
    val refresh = tick
    val total = progress.total
    val done = progress.done
    val percent = if (total > 0) ((done * 100) / total).coerceIn(0L, 100L) else -1L

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShow)
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(if (percent >= 0) "$percent%" else "Em andamento")
                            progress.remainingMs()?.let { append("  •  restam ~").append(formatClock(it)) }
                            append("  •  toque para ver")
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
