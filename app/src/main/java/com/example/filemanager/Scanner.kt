package com.example.filemanager

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext

enum class Category(val label: String, val icon: String, val exts: Set<String>) {
    IMAGES("Imagens", "🖼️", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")),
    VIDEOS("Vídeos", "🎬", setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "flv", "wmv")),
    AUDIO("Áudio", "🎵", setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")),
    DOCS(
        "Documentos", "📄",
        setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "csv", "epub", "rtf", "md")
    ),
    APKS("APKs", "🤖", setOf("apk", "xapk", "apks", "apkm"))
}

fun categoryOf(file: File): Category? {
    val ext = file.extension.lowercase()
    return Category.entries.firstOrNull { ext in it.exts }
}

data class ScanItem(val file: File, val size: Long, val modified: Long)

data class Measure(val files: Int, val folders: Int, val bytes: Long)

data class SpaceReport(
    val totalBytes: Long,
    val freeBytes: Long,
    val scannedBytes: Long,
    val byCategory: List<Pair<String, Long>>,
    val folders: List<Pair<File, Long>>,
    val bigFiles: List<File>
)

data class DupGroup(val size: Long, val files: List<File>) {
    val wasted: Long get() = size * (files.size - 1)
}

/**
 * Varreduras do armazenamento (categorias, espaço, duplicados, medidas).
 * Todas são "suspend" e checam o cancelamento, então param quando a tela é fechada.
 */
object Scanner {

    /** Ignora pastas ocultas e Android/data e Android/obb (o Android bloqueia o acesso). */
    private fun enter(dir: File, root: File): Boolean {
        if (dir == root) return true
        if (dir.name.startsWith(".")) return false
        val parent = dir.parentFile
        if (parent != null && parent.name == "Android" && parent.parentFile == root &&
            (dir.name == "data" || dir.name == "obb")
        ) return false
        return true
    }

    private fun walk(root: File) =
        root.walkTopDown().maxDepth(30).onEnter { enter(it, root) }

    suspend fun findCategory(root: File, cat: Category, limit: Int = 20000): List<ScanItem> {
        val ctx = currentCoroutineContext()
        val out = ArrayList<ScanItem>()
        var n = 0
        for (f in walk(root)) {
            if ((++n and 0xFF) == 0) ctx.ensureActive()
            if (!f.isFile || f.name.startsWith(".")) continue
            if (categoryOf(f) == cat) {
                out.add(ScanItem(f, f.length(), f.lastModified()))
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun sortItems(list: List<ScanItem>, key: SortKey, ascending: Boolean): List<ScanItem> {
        val byKey: Comparator<ScanItem> = when (key) {
            SortKey.NAME -> compareBy<ScanItem> { it.file.name.lowercase() }
            SortKey.DATE -> compareBy<ScanItem> { it.modified }
            SortKey.SIZE -> compareBy<ScanItem> { it.size }
        }
        return list.sortedWith(if (ascending) byKey else byKey.reversed())
    }

    suspend fun analyzeSpace(root: File): SpaceReport {
        val ctx = currentCoroutineContext()
        var total = 0L
        var free = 0L
        runCatching {
            val stat = StatFs(root.path)
            total = stat.totalBytes
            free = stat.availableBytes
        }

        val catSizes = LinkedHashMap<Category?, Long>()
        val topFolders = HashMap<String, Long>()
        val heap = PriorityQueue<Pair<Long, File>>(16, compareBy<Pair<Long, File>> { it.first })
        val rootPath = root.absolutePath
        var scanned = 0L
        var n = 0

        for (f in walk(root)) {
            if ((++n and 0xFF) == 0) ctx.ensureActive()
            if (!f.isFile) continue
            val len = f.length()
            scanned += len
            val cat = categoryOf(f)
            catSizes[cat] = (catSizes[cat] ?: 0L) + len

            val rel = f.absolutePath.removePrefix(rootPath).trimStart('/')
            if (rel.contains('/')) {
                val first = rel.substringBefore('/')
                topFolders[first] = (topFolders[first] ?: 0L) + len
            }

            if (heap.size < 30) {
                heap.add(len to f)
            } else if (len > heap.peek().first) {
                heap.poll()
                heap.add(len to f)
            }
        }

        return SpaceReport(
            totalBytes = total,
            freeBytes = free,
            scannedBytes = scanned,
            byCategory = catSizes.entries
                .sortedByDescending { it.value }
                .map { (it.key?.label ?: "Outros") to it.value },
            folders = topFolders.entries
                .sortedByDescending { it.value }
                .take(15)
                .map { File(root, it.key) to it.value },
            bigFiles = heap.sortedByDescending { it.first }.map { it.second }
        )
    }

    private fun hash(file: File, maxBytes: Long, ctx: CoroutineContext): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var left = maxBytes
            while (left > 0) {
                ctx.ensureActive()
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                md.update(buf, 0, r)
                left -= r
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Acha arquivos idênticos: agrupa por tamanho, depois pelo hash dos primeiros 64 KB
     * e só então confirma com o hash do arquivo inteiro (SHA-256).
     */
    suspend fun findDuplicates(
        root: File,
        minSize: Long = 10_240,
        onProgress: (String) -> Unit
    ): List<DupGroup> {
        val ctx = currentCoroutineContext()
        val bySize = HashMap<Long, MutableList<File>>()
        var n = 0
        for (f in walk(root)) {
            if ((++n and 0xFF) == 0) {
                ctx.ensureActive()
                if ((n and 0x3FF) == 0) onProgress("Lendo arquivos... $n")
            }
            if (!f.isFile || f.name.startsWith(".")) continue
            val len = f.length()
            if (len < minSize) continue
            bySize.getOrPut(len) { ArrayList() }.add(f)
        }

        val candidates = bySize.filterValues { it.size > 1 }
        val total = candidates.values.sumOf { it.size }
        var done = 0
        val groups = ArrayList<DupGroup>()

        for ((size, files) in candidates) {
            ctx.ensureActive()
            val byHead = HashMap<String, MutableList<File>>()
            for (f in files) {
                hash(f, 64L * 1024, ctx)?.let { byHead.getOrPut(it) { ArrayList() }.add(f) }
                done++
            }
            onProgress("Comparando... $done de $total")
            for (headGroup in byHead.values) {
                if (headGroup.size < 2) continue
                val byFull = HashMap<String, MutableList<File>>()
                for (f in headGroup) {
                    hash(f, Long.MAX_VALUE, ctx)?.let { byFull.getOrPut(it) { ArrayList() }.add(f) }
                }
                for (g in byFull.values) {
                    if (g.size > 1) groups.add(DupGroup(size, g.sortedBy { it.lastModified() }))
                }
            }
        }
        return groups.sortedByDescending { it.wasted }.take(500)
    }

    /** Conta arquivos, pastas e bytes dos itens (recursivo). */
    suspend fun measure(files: List<File>): Measure {
        val ctx = currentCoroutineContext()
        var fileCount = 0
        var folderCount = 0
        var bytes = 0L
        var n = 0
        for (top in files) {
            for (f in top.walkTopDown().maxDepth(40)) {
                if ((++n and 0xFF) == 0) ctx.ensureActive()
                if (f.isDirectory) {
                    if (f != top) folderCount++
                } else {
                    fileCount++
                    bytes += f.length()
                }
            }
        }
        return Measure(fileCount, folderCount, bytes)
    }
}
