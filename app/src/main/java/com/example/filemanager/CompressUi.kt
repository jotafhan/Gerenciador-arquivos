package com.example.filemanager

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
                        valueLabel = if (canLevel) level.label else "—",
                        options = LevelOption.entries.map { it.label to it },
                        enabled = canLevel,
                        modifier = Modifier.weight(1f)
                    ) { level = it }
                }
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
                            level = level,
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
