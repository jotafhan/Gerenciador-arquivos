package com.example.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.text.DateFormat
import java.util.Date

/** Gera e guarda em cache miniaturas de imagens, vídeos, capas de áudio, PDFs e ícones de APK. */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // Só 2 miniaturas ao mesmo tempo e em prioridade baixa, para não competir com a rolagem
    private val dispatcher = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }
    }.asCoroutineDispatcher()

    fun clear() {
        cache.evictAll()
    }

    fun supports(file: File, isDir: Boolean): Boolean {
        if (isDir) return false
        val ext = file.extension.lowercase()
        if (ext == "apk" || ext == "pdf") return true
        val cat = categoryOf(file)
        return (cat == Category.IMAGES && ext != "svg") || cat == Category.VIDEOS || cat == Category.AUDIO
    }

    suspend fun load(context: Context, file: File, px: Int, modified: Long): Bitmap? {
        val key = "${file.absolutePath}|$modified|$px"
        cache.get(key)?.let { return it }
        val bitmap = withContext(dispatcher) { runCatching { create(context, file, px) }.getOrNull() }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun create(context: Context, file: File, px: Int): Bitmap? {
        val ext = file.extension.lowercase()
        val category = categoryOf(file)
        val source = when {
            ext == "apk" -> apkIcon(context, file, px)
            ext == "pdf" -> pdfPage(file, px)
            category == Category.IMAGES -> image(file, px)
            category == Category.VIDEOS -> videoFrame(file, px)
            category == Category.AUDIO -> audioCover(file)
            else -> null
        } ?: return null
        return ThumbnailUtils.extractThumbnail(source, px, px)
    }

    private fun image(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sample * 2) >= px) sample *= 2
        var bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        // Fotos de câmera guardam a rotação no EXIF
        val ext = file.extension.lowercase()
        if (ext == "jpg" || ext == "jpeg") {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        }
        return bitmap
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } finally {
            retriever.release()
        }
    }

    private fun audioCover(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val data = retriever.embeddedPicture ?: return null
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        } finally {
            retriever.release()
        }
    }

    private fun pdfPage(file: File, px: Int): Bitmap? {
        var result: Bitmap? = null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { page ->
                        val width = px
                        val height = (px.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val page0 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page0.eraseColor(android.graphics.Color.WHITE)
                        page.render(page0, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // Só a parte de cima da página, em formato quadrado
                        result = Bitmap.createBitmap(page0, 0, 0, width, minOf(height, width))
                    }
                }
            }
        }
        return result
    }

    private fun apkIcon(context: Context, file: File, px: Int): Bitmap? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = file.absolutePath
        ai.publicSourceDir = file.absolutePath
        return ai.loadIcon(pm).toBitmap(px, px)
    }
}

/** Ícone do arquivo: miniatura quando existir; senão um quadrado colorido com o tipo. */
@Composable
internal fun FileIcon(file: File, size: Dp, modifier: Modifier = Modifier, entry: FileEntry? = null) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    // Sem os dados prontos, lê uma vez e guarda (não consulta o armazenamento a cada recomposição)
    val fallback = remember(file.absolutePath, entry) { if (entry == null) FileOps.entryOf(file) else null }
    val info = entry ?: fallback ?: FileEntry(file, false, 0L, 0L)
    val modified = info.modified
    val showThumbs = AppSettings.showThumbnails
    val thumb by produceState<ImageBitmap?>(null, file.absolutePath, modified, px, showThumbs) {
        value = if (showThumbs && Thumbnails.supports(file, info.isDir)) {
            Thumbnails.load(context, file, px, modified)?.asImageBitmap()
        } else {
            null
        }
    }
    val bitmap = thumb
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(10.dp))
        )
    } else {
        val isDir = info.isDir
        val cat = categoryOf(file)
        val background = when {
            isDir -> Color(0xFF2A3F9F)
            cat != null -> colorFor(cat)
            else -> Color(0xFF455A64)
        }
        val shape = if (isDir) CircleShape else RoundedCornerShape(10.dp)
        val ext = file.extension.uppercase().take(4)
        val isEmoji = isDir || cat != null || ext.isEmpty()
        val label = when {
            isDir -> "📁"
            cat != null -> cat.icon
            ext.isNotEmpty() -> ext
            else -> "📄"
        }
        Box(
            modifier = modifier.size(size).clip(shape).background(background),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = (size.value * (if (isEmoji) 0.5f else 0.26f)).sp,
                maxLines = 1,
                softWrap = false,
                color = Color.White
            )
        }
    }
}

/** Célula da visualização em grade. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileCell(
    file: File,
    folderSize: Long?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    entry: FileEntry? = null
) {
    val context = LocalContext.current
    val fallback = remember(file.absolutePath, entry) { if (entry == null) FileOps.entryOf(file) else null }
    val info = entry ?: fallback ?: FileEntry(file, false, 0L, 0L)
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            FileIcon(file, 72.dp, entry = info)
            if (selectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp)
                }
            }
        }
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        val detail = if (info.isDir) {
            folderSize?.let { android.text.format.Formatter.formatFileSize(context, it) }
        } else {
            android.text.format.Formatter.formatFileSize(context, info.size)
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
