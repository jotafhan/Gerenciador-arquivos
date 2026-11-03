package com.example.filemanager

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ Escolher pasta

/** Diálogo para escolher a pasta de destino (usado em "Copiar para..." e "Mover para..."). */
@Composable
internal fun FolderPickerDialog(
    title: String,
    root: File,
    start: File,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    var dir by remember { mutableStateOf(start) }
    val folders = remember(dir) {
        (dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
            .sortedBy { it.name.lowercase() }
    }
    val canGoUp = dir.absolutePath != root.absolutePath && dir.parentFile != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    dir.absolutePath,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (canGoUp) {
                        FolderRow("⬆️", "Pasta acima") { dir = dir.parentFile ?: dir }
                    }
                    folders.forEach { f -> FolderRow("📁", f.name) { dir = f } }
                    if (folders.isEmpty()) {
                        Text("Sem subpastas", modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(dir) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun FolderRow(icon: String, name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Text(icon)
        Spacer(Modifier.width(12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ Metadados

@Composable
internal fun MetadataDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(file) {
        rows = withContext(Dispatchers.IO) { MetadataReader.read(context, file) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val list = rows
                when {
                    list == null -> Text("Lendo...")
                    list.isEmpty() -> Text("Nenhum metadado disponível para este tipo de arquivo")
                    else -> list.forEach { (label, value) -> Text("$label: $value") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

/** Lê metadados de imagens (EXIF), áudio/vídeo (MediaMetadataRetriever) e APKs. */
object MetadataReader {

    fun read(context: Context, file: File): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val category = categoryOf(file)
        runCatching {
            when {
                file.extension.equals("apk", ignoreCase = true) -> readApk(context, file, out)
                category == Category.IMAGES -> readImage(file, out)
                category == Category.VIDEOS || category == Category.AUDIO -> readMedia(file, out)
                else -> {}
            }
        }
        return out
    }

    private fun readImage(file: File, out: MutableList<Pair<String, String>>) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth > 0) out.add("Dimensões" to "${options.outWidth} × ${options.outHeight} px")
        options.outMimeType?.let { out.add("Tipo" to it) }

        if (file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
            val exif = ExifInterface(file.absolutePath)
            fun tag(label: String, key: String) {
                exif.getAttribute(key)?.takeIf { it.isNotBlank() }?.let { out.add(label to it) }
            }
            tag("Data da foto", ExifInterface.TAG_DATETIME_ORIGINAL)
            tag("Fabricante", ExifInterface.TAG_MAKE)
            tag("Modelo", ExifInterface.TAG_MODEL)
            tag("Exposição (s)", ExifInterface.TAG_EXPOSURE_TIME)
            tag("Abertura (f)", ExifInterface.TAG_F_NUMBER)
            tag("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            tag("Distância focal", ExifInterface.TAG_FOCAL_LENGTH)
            tag("Software", ExifInterface.TAG_SOFTWARE)
            val gps = exif.latLong
            if (gps != null) out.add("Localização (GPS)" to "%.6f, %.6f".format(gps[0], gps[1]))
        }
    }

    private fun readMedia(file: File, out: MutableList<Pair<String, String>>) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun key(label: String, k: Int) {
                retriever.extractMetadata(k)?.takeIf { it.isNotBlank() }?.let { out.add(label to it) }
            }
            key("Título", MediaMetadataRetriever.METADATA_KEY_TITLE)
            key("Artista", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            key("Álbum", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            key("Gênero", MediaMetadataRetriever.METADATA_KEY_GENRE)
            key("Data", MediaMetadataRetriever.METADATA_KEY_DATE)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.let { out.add("Duração" to formatDuration(it)) }
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) out.add("Resolução" to "$w × $h px")
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                ?.let { out.add("Taxa de bits" to "${it / 1000} kb/s") }
            key("Tipo", MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } finally {
            retriever.release()
        }
    }

    private fun readApk(context: Context, file: File, out: MutableList<Pair<String, String>>) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return
        val ai = info.applicationInfo
        if (ai != null) {
            ai.sourceDir = file.absolutePath
            ai.publicSourceDir = file.absolutePath
            out.add("Nome" to pm.getApplicationLabel(ai).toString())
        }
        out.add("Pacote" to info.packageName)
        info.versionName?.let { out.add("Versão" to it) }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        out.add("Código da versão" to code.toString())
        if (ai != null) {
            out.add("SDK mínimo" to ai.minSdkVersion.toString())
            out.add("SDK alvo" to ai.targetSdkVersion.toString())
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
