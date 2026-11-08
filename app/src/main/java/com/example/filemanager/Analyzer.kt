package com.example.filemanager

import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.PriorityQueue

/** Define qual pasta a próxima análise examina (null = o armazenamento todo). */
object AnalysisTarget {
    var path: String? = null

    fun take(): String? {
        val p = path
        path = null
        return p
    }
}

/** Contadores ao vivo da análise (lidos pela tela). */
class ScanProgress {
    @Volatile var files: Int = 0
    @Volatile var folders: Int = 0
    @Volatile var bytes: Long = 0L
}

data class AnalysisResult(
    val root: File,
    val totalBytes: Long,
    val freeBytes: Long,
    val scannedBytes: Long,
    val fileCount: Int,
    val byCategory: List<Pair<String, Long>>,
    val bigFiles: List<ScanItem>,
    val recentFiles: List<ScanItem>,
    val emptyFolders: List<ScanItem>,
    val residualFiles: List<ScanItem>,
    val installedApks: List<ScanItem>
)

/** Análise completa de uma pasta: tipos, maiores, recentes, vazias, residuais e APKs já instalados. */
object Analyzer {
    private val RESIDUAL_EXTENSIONS =
        listOf(".tmp", ".temp", ".log", ".bak", ".old", ".crdownload", ".part", ".partial", ".thumbdata", ".dmp")

    // Não entra em Android/data e Android/obb (bloqueados pelo Android) nem na lixeira do app
    private fun enter(dir: File, root: File): Boolean {
        if (dir == root) return true
        if (dir.name.startsWith(".")) return false
        val parent = dir.parentFile
        if (parent != null && parent.name == "Android" && (dir.name == "data" || dir.name == "obb")) return false
        return true
    }

    private fun push(heap: PriorityQueue<Pair<Long, ScanItem>>, key: Long, item: ScanItem, limit: Int) {
        if (heap.size < limit) {
            heap.add(key to item)
        } else if (key > heap.peek().first) {
            heap.poll()
            heap.add(key to item)
        }
    }

    suspend fun analyze(root: File, context: Context, progress: ScanProgress): AnalysisResult {
        val ctx = currentCoroutineContext()
        var total = 0L
        var free = 0L
        runCatching {
            val stat = StatFs(root.path)
            total = stat.totalBytes
            free = stat.availableBytes
        }

        val categorySizes = LinkedHashMap<Category?, Long>()
        val big = PriorityQueue<Pair<Long, ScanItem>>(16, compareBy<Pair<Long, ScanItem>> { it.first })
        val recent = PriorityQueue<Pair<Long, ScanItem>>(16, compareBy<Pair<Long, ScanItem>> { it.first })
        val emptyFolders = ArrayList<ScanItem>()
        val residual = ArrayList<ScanItem>()
        val apks = ArrayList<ScanItem>()
        var scanned = 0L
        var n = 0

        val walk = root.walkTopDown().maxDepth(30).onEnter { dir ->
            if (!enter(dir, root)) {
                false
            } else {
                progress.folders++
                if (dir != root && dir.list()?.isEmpty() == true) {
                    emptyFolders.add(ScanItem(dir, 0L, dir.lastModified()))
                }
                true
            }
        }

        for (f in walk) {
            if ((++n and 0xFF) == 0) ctx.ensureActive()
            if (f == root || f.isDirectory) continue
            val e = FileOps.entryOf(f) // tipo, tamanho e data numa única consulta
            if (e.isDir) continue
            val item = ScanItem(f, e.size, e.modified)
            scanned += e.size
            progress.files++
            progress.bytes = scanned

            val cat = categoryOf(f)
            categorySizes[cat] = (categorySizes[cat] ?: 0L) + e.size
            push(big, e.size, item, 50)

            val lower = f.name.lowercase()
            val hidden = f.name.startsWith(".")
            if (!hidden) push(recent, e.modified, item, 50)
            if ((e.size == 0L && !hidden) || RESIDUAL_EXTENSIONS.any { lower.endsWith(it) } || lower.endsWith("~")) {
                residual.add(item)
            }
            if (lower.endsWith(".apk") && !f.absolutePath.contains("/APKs_salvos/")) apks.add(item)
        }

        val installed = apks.filter { isAlreadyInstalled(context, it.file) }

        return AnalysisResult(
            root = root,
            totalBytes = total,
            freeBytes = free,
            scannedBytes = scanned,
            fileCount = progress.files,
            byCategory = categorySizes.entries
                .sortedByDescending { it.value }
                .map { (it.key?.label ?: "Outros") to it.value },
            bigFiles = big.sortedByDescending { it.first }.map { it.second },
            recentFiles = recent.sortedByDescending { it.first }.map { it.second },
            emptyFolders = emptyFolders.sortedBy { it.file.absolutePath }.take(300),
            residualFiles = residual.sortedByDescending { it.size }.take(500),
            installedApks = installed.sortedByDescending { it.size }.take(200)
        )
    }

    /** true se o APK é de um app já instalado, na mesma versão ou em uma mais nova. */
    @Suppress("DEPRECATION")
    private fun isAlreadyInstalled(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val installed = try {
            pm.getPackageInfo(archive.packageName, 0)
        } catch (e: Exception) {
            return false
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        return installedCode >= archiveCode
    }
}
