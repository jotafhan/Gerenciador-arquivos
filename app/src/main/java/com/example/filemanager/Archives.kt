package com.example.filemanager

import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.tukaani.xz.LZMA2Options
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile as JavaZipFile

// ------------------------------------------------------------------ Opções de compactação

enum class ArchiveFormat(val ext: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    TAR("tar"),
    TAR_GZ("tar.gz"),
    TAR_BZ2("tar.bz2"),
    TAR_XZ("tar.xz")
}

enum class LevelOption(val label: String) {
    STORE("Sem compressão"),
    FAST("Rápido"),
    NORMAL("Normal"),
    MAX("Máximo")
}

enum class EncryptionOption(val label: String) {
    NONE("Nenhuma"),
    ZIP_CRYPTO("Dados (ZipCrypto, fraca)"),
    AES256("AES-256")
}

enum class SplitOption(val label: String, val bytes: Long?) {
    NONE("Não", null),
    MB10("10 MB", 10L * 1024 * 1024),
    MB50("50 MB", 50L * 1024 * 1024),
    MB100("100 MB", 100L * 1024 * 1024),
    MB650("650 MB", 650L * 1024 * 1024),
    GB1("1 GB", 1024L * 1024 * 1024),
    GB2("2 GB", 2048L * 1024 * 1024)
}

data class CompressOptions(
    val format: ArchiveFormat,
    val level: LevelOption,
    val encryption: EncryptionOption,
    val password: String,
    val splitBytes: Long?,
    val separate: Boolean,
    val deleteAfter: Boolean
)

/** O arquivo está protegido por senha (ou a senha digitada estava errada). */
class PasswordRequiredException(val wrong: Boolean) :
    Exception(if (wrong) "Senha incorreta" else "Este arquivo está protegido por senha")

/** O usuário cancelou a operação. */
class OperationCancelledException : Exception("Cancelado")

/** Progresso de uma operação longa. Lido pela tela e escrito pela thread de trabalho. */
class OpProgress {
    @Volatile var total: Long = 0L // 0 = desconhecido
    @Volatile var done: Long = 0L
    @Volatile var label: String = ""
    @Volatile var cancelled: Boolean = false

    fun check() {
        if (cancelled) throw OperationCancelledException()
    }
}

/** Uma entrada dentro de um arquivo compactado (para a tela "Ver conteúdo"). */
data class ArchiveEntryInfo(val path: String, val isDir: Boolean, val size: Long, val modified: Long)

// Buffers grandes: no armazenamento do Android cada leitura/escrita pequena é muito cara
private const val BUF = 256 * 1024

private fun norm(path: String): String = path.replace('\\', '/').trim('/')

private fun copyStream(input: InputStream, output: OutputStream, progress: OpProgress?, count: Boolean) {
    val buf = ByteArray(BUF)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        if (progress != null) {
            progress.check()
            if (count) progress.done += n
        }
    }
}

private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) :
    FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            count++
            onBytes(count)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            count += n
            onBytes(count)
        }
        return n
    }
}

private class CountingOutputStream(output: OutputStream, private val progress: OpProgress?) :
    FilterOutputStream(output) {
    override fun write(b: Int) {
        out.write(b)
        if (progress != null) {
            progress.done += 1
            progress.check()
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        if (progress != null) {
            progress.done += len
            progress.check()
        }
    }
}

/**
 * Compactar, extrair e listar vários formatos.
 * Extrai: ZIP (inclusive com senha e em volumes), 7z, RAR (só RAR4), TAR, TAR.GZ/TGZ, TAR.BZ2, TAR.XZ, GZ, BZ2, XZ.
 * Compacta: ZIP (com senha e volumes), 7z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ.
 */
object Archives {

    // Os mais longos primeiro (".tar.gz" antes de ".gz")
    private val EXTENSIONS = listOf(
        ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz",
        ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz"
    )

    fun isArchive(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        return EXTENSIONS.any { name.endsWith(it) }
    }

    /** ZIP e formatos que são ZIPs por dentro (APK, APKS, XAPK...). */
    private fun isZipLike(lower: String): Boolean =
        lower.endsWith(".zip") || lower.endsWith(".apk") || lower.endsWith(".apks") ||
            lower.endsWith(".xapk") || lower.endsWith(".apkm") || lower.endsWith(".jar")

    private fun baseNameOf(file: File): String {
        val lower = file.name.lowercase()
        val ext = EXTENSIONS.firstOrNull { lower.endsWith(it) }
        val base = if (ext != null) file.name.dropLast(ext.length) else file.name.substringBeforeLast('.')
        return base.ifEmpty { "extraido" }
    }

    /** Pasta onde "Extrair" coloca os arquivos: ao lado do arquivo compactado, com o nome dele. */
    fun defaultExtractDir(archive: File): File = File(archive.parentFile, baseNameOf(archive))

    private fun uniqueTarget(dir: File, base: String, ext: String): File {
        var target = File(dir, "$base.$ext")
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i).$ext")
            i++
        }
        return target
    }

    private fun openZip(archive: File): JavaZipFile =
        try {
            JavaZipFile(archive)
        } catch (e: IllegalArgumentException) {
            // Nomes em codificação antiga (CP437/Latin-1)
            JavaZipFile(archive, Charsets.ISO_8859_1)
        }

    // ------------------------------------------------------------ Listar

    fun list(archive: File): Result<List<ArchiveEntryInfo>> = runCatching {
        val lower = archive.name.lowercase()
        when {
            isZipLike(lower) -> listZip(archive)
            lower.endsWith(".7z") -> list7z(archive)
            lower.endsWith(".rar") -> listRar(archive)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> listTar(archive) { GzipCompressorInputStream(it) }
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> listTar(archive) { BZip2CompressorInputStream(it) }
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> listTar(archive) { XZCompressorInputStream(it) }
            lower.endsWith(".tar") -> listTar(archive) { it }
            lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz") ->
                listOf(ArchiveEntryInfo(archive.nameWithoutExtension, false, -1L, archive.lastModified()))
            else -> error("Formato não suportado")
        }
    }

    private fun listZip(archive: File): List<ArchiveEntryInfo> =
        try {
            openZip(archive).use { zf ->
                zf.entries().toList().map { e ->
                    ArchiveEntryInfo(norm(e.name), e.isDirectory, if (e.isDirectory) 0L else e.size, e.time)
                }
            }
        } catch (e: Exception) {
            // ZIP em volumes: o zip4j sabe ler o índice
            ZipFile(archive).use { z ->
                z.fileHeaders.map { h ->
                    ArchiveEntryInfo(norm(h.fileName), h.isDirectory, if (h.isDirectory) 0L else h.uncompressedSize, 0L)
                }
            }
        }

    private fun list7z(archive: File): List<ArchiveEntryInfo> =
        SevenZFile.builder().setFile(archive).get().use { sz ->
            sz.entries.map { e ->
                val modified = runCatching { e.lastModifiedDate.time }.getOrDefault(0L)
                ArchiveEntryInfo(norm(e.name), e.isDirectory, if (e.isDirectory) 0L else e.size, modified)
            }
        }

    private fun listRar(archive: File): List<ArchiveEntryInfo> =
        try {
            Archive(archive).use { rar ->
                rar.fileHeaders.map { h ->
                    ArchiveEntryInfo(norm(h.fileName), h.isDirectory, if (h.isDirectory) 0L else h.fullUnpackSize, 0L)
                }
            }
        } catch (e: Exception) {
            throw friendly(e)
        }

    private fun listTar(archive: File, wrap: (InputStream) -> InputStream): List<ArchiveEntryInfo> {
        val out = ArrayList<ArchiveEntryInfo>()
        val input = wrap(BufferedInputStream(FileInputStream(archive), BUF))
        TarArchiveInputStream(input).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                out.add(
                    ArchiveEntryInfo(
                        norm(entry.name),
                        entry.isDirectory,
                        if (entry.isDirectory) 0L else entry.size,
                        entry.lastModifiedDate?.time ?: 0L
                    )
                )
                entry = tis.nextEntry
            }
        }
        return out
    }

    // ------------------------------------------------------------ Extrair

    private fun isZipEncrypted(file: File): Boolean =
        runCatching { ZipFile(file).isEncrypted }.getOrDefault(false)

    private fun hasSplitParts(zip: File): Boolean =
        File(zip.parentFile, zip.name.dropLast(4) + ".z01").exists()

    /** Extrai tudo para uma pasta nova com o nome do arquivo, ao lado dele. */
    fun extract(
        archive: File,
        destDir: File,
        password: String? = null,
        progress: OpProgress? = null
    ): Result<File> = runCatching {
        val target = FileOps.uniqueTarget(destDir, baseNameOf(archive))
        if (!target.mkdirs()) error("Não foi possível criar a pasta de destino")
        try {
            extractInto(archive, target, password, progress, null)
        } catch (e: Exception) {
            target.deleteRecursively()
            throw if (e is PasswordRequiredException || e is OperationCancelledException) e else friendly(e)
        }
        target
    }

    /** Extrai só as entradas aceitas por [filter] (nome cru dentro do arquivo) direto para [destDir]. */
    fun extractEntries(
        archive: File,
        destDir: File,
        password: String?,
        filter: (String) -> Boolean,
        progress: OpProgress? = null
    ): Result<File> = runCatching {
        destDir.mkdirs()
        try {
            extractInto(archive, destDir, password, progress, filter)
        } catch (e: Exception) {
            throw if (e is PasswordRequiredException || e is OperationCancelledException) e else friendly(e)
        }
        destDir
    }

    private fun extractInto(
        archive: File,
        target: File,
        password: String?,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        val lower = archive.name.lowercase()
        when {
            isZipLike(lower) -> extractZipAny(archive, target, password, progress, filter)
            lower.endsWith(".7z") -> extract7z(archive, target, progress, filter)
            lower.endsWith(".rar") -> extractRar(archive, target, progress, filter)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                extractTar(archive, target, progress, filter) { GzipCompressorInputStream(it) }
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ->
                extractTar(archive, target, progress, filter) { BZip2CompressorInputStream(it) }
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") ->
                extractTar(archive, target, progress, filter) { XZCompressorInputStream(it) }
            lower.endsWith(".tar") -> extractTar(archive, target, progress, filter) { it }
            lower.endsWith(".gz") -> extractSingle(archive, target, ".gz", progress) { GzipCompressorInputStream(it) }
            lower.endsWith(".bz2") -> extractSingle(archive, target, ".bz2", progress) { BZip2CompressorInputStream(it) }
            lower.endsWith(".xz") -> extractSingle(archive, target, ".xz", progress) { XZCompressorInputStream(it) }
            else -> error("Formato não suportado")
        }
    }

    private fun friendly(e: Exception): Exception {
        val kind = e.javaClass.simpleName
        return when {
            kind.contains("V5") ->
                IllegalStateException("RAR5 ainda não é suportado (só RAR4). Extraia no computador.")
            kind.contains("Encrypted") || e.message?.contains("password", ignoreCase = true) == true ->
                IllegalStateException("Arquivo protegido por senha (só ZIP com senha é suportado)")
            else -> e
        }
    }

    /** Resolve o caminho de uma entrada dentro da pasta de destino, bloqueando "../" malicioso. */
    private fun resolveSafe(target: File, name: String): File? {
        val clean = name.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean == ".") return null
        val out = File(target, clean)
        val base = target.canonicalPath
        val path = out.canonicalPath
        if (path != base && !path.startsWith(base + File.separator)) {
            error("Arquivo inválido: caminho fora da pasta de destino")
        }
        return out
    }

    private fun extractZipAny(
        archive: File,
        target: File,
        password: String?,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        val encrypted = isZipEncrypted(archive)
        if (!encrypted && password == null && !hasSplitParts(archive)) {
            extractPlainZip(archive, target, progress, filter)
            return
        }
        if (encrypted && password.isNullOrEmpty()) throw PasswordRequiredException(false)
        try {
            extractZip4j(archive, target, password, progress, filter)
        } catch (e: ZipException) {
            if (progress?.cancelled == true) throw OperationCancelledException()
            if (e.type == ZipException.Type.WRONG_PASSWORD) throw PasswordRequiredException(true)
            throw e
        }
    }

    /** ZIP comum: leitura direta por entrada (rápida) com buffer grande. */
    private fun extractPlainZip(
        archive: File,
        target: File,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        openZip(archive).use { zf ->
            val entries = zf.entries().toList()
            if (progress != null && filter == null) {
                progress.total = entries.sumOf { maxOf(it.size, 0L) }
            }
            for (entry in entries) {
                progress?.check()
                val name = entry.name
                if (filter != null && !filter(name)) continue
                val out = resolveSafe(target, name) ?: continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    progress?.label = name
                    zf.getInputStream(entry).use { ins ->
                        FileOutputStream(out).use { os -> copyStream(ins, os, progress, true) }
                    }
                }
            }
        }
    }

    private fun extractZip4j(
        archive: File,
        target: File,
        password: String?,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        val zip = if (password.isNullOrEmpty()) ZipFile(archive) else ZipFile(archive, password.toCharArray())
        zip.setBufferSize(BUF)
        zip.use { z ->
            if (filter == null) {
                withMonitor(z, progress) { z.extractAll(target.absolutePath) }
            } else {
                for (h in z.fileHeaders.filter { filter(it.fileName) }) {
                    progress?.check()
                    z.extractFile(h, target.absolutePath)
                }
            }
        }
    }

    /** Lê o progresso do zip4j em outra thread e repassa o cancelamento. */
    private fun <T> withMonitor(zip: ZipFile, progress: OpProgress?, block: () -> T): T {
        if (progress == null) return block()
        val monitor = zip.progressMonitor
        val running = AtomicBoolean(true)
        val poller = Thread {
            while (running.get()) {
                progress.done = monitor.workCompleted
                progress.total = monitor.totalWork
                monitor.fileName?.let { progress.label = it }
                if (progress.cancelled) monitor.isCancelAllTasks = true
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        poller.isDaemon = true
        poller.start()
        try {
            return block()
        } finally {
            running.set(false)
            poller.interrupt()
        }
    }

    private fun extract7z(
        archive: File,
        target: File,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            if (progress != null && filter == null) {
                progress.total = sz.entries.sumOf { if (it.isDirectory) 0L else it.size }
            }
            val buf = ByteArray(BUF)
            var entry = sz.nextEntry
            while (entry != null) {
                progress?.check()
                val name = entry.name
                val out = if (filter != null && !filter(name)) null else resolveSafe(target, name)
                if (out != null) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        progress?.label = name
                        FileOutputStream(out).use { os ->
                            while (true) {
                                val n = sz.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                if (progress != null) {
                                    progress.check()
                                    progress.done += n
                                }
                            }
                        }
                    }
                }
                entry = sz.nextEntry
            }
        }
    }

    private fun extractRar(
        archive: File,
        target: File,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?
    ) {
        Archive(archive).use { rar ->
            if (progress != null && filter == null) {
                progress.total = rar.fileHeaders.sumOf { if (it.isDirectory) 0L else it.fullUnpackSize }
            }
            var header = rar.nextFileHeader()
            while (header != null) {
                progress?.check()
                val h = header
                val name = h.fileName
                val out = if (filter != null && !filter(name)) null else resolveSafe(target, name)
                if (out != null) {
                    if (h.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        progress?.label = name
                        CountingOutputStream(FileOutputStream(out), progress).use { os -> rar.extractFile(h, os) }
                    }
                }
                header = rar.nextFileHeader()
            }
        }
    }

    private fun extractTar(
        archive: File,
        target: File,
        progress: OpProgress?,
        filter: ((String) -> Boolean)?,
        wrap: (InputStream) -> InputStream
    ) {
        // Progresso pelos bytes lidos do arquivo compactado (funciona também com .gz/.bz2/.xz)
        progress?.total = archive.length()
        val counting = CountingInputStream(FileInputStream(archive)) { progress?.done = it }
        val input = wrap(BufferedInputStream(counting, BUF))
        TarArchiveInputStream(input).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                progress?.check()
                val name = entry.name
                val out = if (filter != null && !filter(name)) null else resolveSafe(target, name)
                if (out != null) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        progress?.label = name
                        FileOutputStream(out).use { os -> copyStream(tis, os, progress, false) }
                    }
                }
                entry = tis.nextEntry
            }
        }
    }

    private fun extractSingle(
        archive: File,
        target: File,
        ext: String,
        progress: OpProgress?,
        wrap: (InputStream) -> InputStream
    ) {
        progress?.total = archive.length()
        val name = archive.name.dropLast(ext.length).ifEmpty { "arquivo" }
        progress?.label = name
        val counting = CountingInputStream(FileInputStream(archive)) { progress?.done = it }
        wrap(BufferedInputStream(counting, BUF)).use { input ->
            FileOutputStream(File(target, name)).use { os -> copyStream(input, os, progress, false) }
        }
    }

    // ------------------------------------------------------------ Compactar

    private fun totalSizeOf(files: List<File>): Long =
        files.sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Cria o(s) arquivo(s) compactado(s) em [destDir]. Devolve os arquivos criados. */
    fun compress(
        files: List<File>,
        destDir: File,
        baseName: String,
        options: CompressOptions,
        progress: OpProgress? = null
    ): Result<List<File>> = runCatching {
        require(files.isNotEmpty()) { "Nada para compactar" }
        val groups = if (options.separate && files.size > 1) files.map { listOf(it) } else listOf(files)
        val created = ArrayList<File>()
        for (group in groups) {
            val first = group.first()
            val name = if (groups.size > 1) {
                if (first.isDirectory) first.name else first.nameWithoutExtension
            } else {
                baseName
            }
            val target = uniqueTarget(destDir, name.ifBlank { "Arquivos" }, options.format.ext)
            if (progress != null) {
                progress.done = 0L
                progress.total = if (options.format == ArchiveFormat.ZIP) 0L else totalSizeOf(group)
                progress.label = target.name
            }
            try {
                write(group, target, options, progress)
            } catch (e: Exception) {
                cleanup(target, options)
                if (progress?.cancelled == true) throw OperationCancelledException()
                throw e
            }
            created.add(target)
        }
        created
    }

    /** Remove o arquivo (e os volumes .z01, .z02...) de uma compactação que falhou. */
    private fun cleanup(target: File, options: CompressOptions) {
        target.delete()
        if (options.format == ArchiveFormat.ZIP) {
            val stem = target.name.removeSuffix(".zip")
            target.parentFile?.listFiles()
                ?.filter { Regex("""\.z\d+$""").containsMatchIn(it.name) && it.name.startsWith("$stem.z") }
                ?.forEach { it.delete() }
        }
    }

    private fun gzLevel(l: LevelOption) = when (l) {
        LevelOption.STORE -> 0
        LevelOption.FAST -> 1
        LevelOption.NORMAL -> 6
        LevelOption.MAX -> 9
    }

    private fun bzBlock(l: LevelOption) = when (l) {
        LevelOption.STORE, LevelOption.FAST -> 1
        LevelOption.NORMAL -> 5
        LevelOption.MAX -> 9
    }

    // Presets baixos para não estourar a memória do celular (o 6 usa ~94 MB)
    private fun xzPreset(l: LevelOption) = when (l) {
        LevelOption.STORE, LevelOption.FAST -> 1
        LevelOption.NORMAL -> 4
        LevelOption.MAX -> 6
    }

    private fun write(files: List<File>, target: File, o: CompressOptions, progress: OpProgress?) {
        when (o.format) {
            ArchiveFormat.ZIP -> writeZip(files, target, o, progress)
            ArchiveFormat.SEVEN_Z -> write7z(files, target, o.level, progress)
            ArchiveFormat.TAR -> writeTar(files, target, progress) { it }
            ArchiveFormat.TAR_GZ -> writeTar(files, target, progress) {
                GzipCompressorOutputStream(it, GzipParameters().apply { compressionLevel = gzLevel(o.level) })
            }
            ArchiveFormat.TAR_BZ2 -> writeTar(files, target, progress) {
                BZip2CompressorOutputStream(it, bzBlock(o.level))
            }
            ArchiveFormat.TAR_XZ -> writeTar(files, target, progress) {
                XZCompressorOutputStream(it, xzPreset(o.level))
            }
        }
    }

    private fun writeZip(files: List<File>, target: File, o: CompressOptions, progress: OpProgress?) {
        val params = ZipParameters()
        if (o.level == LevelOption.STORE) {
            params.compressionMethod = CompressionMethod.STORE
        } else {
            params.compressionLevel = when (o.level) {
                LevelOption.FAST -> CompressionLevel.FAST
                LevelOption.MAX -> CompressionLevel.MAXIMUM
                else -> CompressionLevel.NORMAL
            }
        }
        val encrypt = o.encryption != EncryptionOption.NONE && o.password.isNotEmpty()
        if (encrypt) {
            params.isEncryptFiles = true
            if (o.encryption == EncryptionOption.AES256) {
                params.encryptionMethod = EncryptionMethod.AES
                params.aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            } else {
                params.encryptionMethod = EncryptionMethod.ZIP_STANDARD
            }
        }
        val zip = if (encrypt) ZipFile(target, o.password.toCharArray()) else ZipFile(target)
        zip.setBufferSize(BUF)
        zip.use { z ->
            withMonitor(z, progress) {
                val split = o.splitBytes
                if (split != null) {
                    z.createSplitZipFile(files, params, true, split)
                } else {
                    for (f in files) {
                        if (f.isDirectory) z.addFolder(f, params) else z.addFile(f, params)
                    }
                }
            }
        }
    }

    private fun write7z(files: List<File>, target: File, level: LevelOption, progress: OpProgress?) {
        SevenZOutputFile(target).use { out ->
            if (level == LevelOption.STORE) {
                out.setContentCompression(SevenZMethod.COPY)
            } else {
                out.setContentMethods(
                    listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(xzPreset(level))))
                )
            }
            val buf = ByteArray(BUF)
            fun add(file: File, path: String) {
                progress?.check()
                val isDir = file.isDirectory
                val entry = SevenZArchiveEntry()
                entry.setName(path)
                entry.setDirectory(isDir)
                entry.setHasStream(!isDir)
                entry.setLastModifiedDate(Date(file.lastModified()))
                if (!isDir) entry.setSize(file.length())
                out.putArchiveEntry(entry)
                if (isDir) {
                    out.closeArchiveEntry()
                    file.listFiles()?.forEach { add(it, "$path/${it.name}") }
                } else {
                    progress?.label = file.name
                    file.inputStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (progress != null) {
                                progress.check()
                                progress.done += n
                            }
                        }
                    }
                    out.closeArchiveEntry()
                }
            }
            files.forEach { add(it, it.name) }
            out.finish()
        }
    }

    private fun writeTar(
        files: List<File>,
        target: File,
        progress: OpProgress?,
        wrap: (OutputStream) -> OutputStream
    ) {
        val raw = BufferedOutputStream(FileOutputStream(target), BUF)
        TarArchiveOutputStream(wrap(raw)).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            fun add(file: File, path: String) {
                progress?.check()
                if (file.isDirectory) {
                    val entry = TarArchiveEntry("$path/")
                    entry.setModTime(Date(file.lastModified()))
                    tos.putArchiveEntry(entry)
                    tos.closeArchiveEntry()
                    file.listFiles()?.forEach { add(it, "$path/${it.name}") }
                } else {
                    val entry = TarArchiveEntry(path)
                    entry.setSize(file.length())
                    entry.setModTime(Date(file.lastModified()))
                    tos.putArchiveEntry(entry)
                    progress?.label = file.name
                    file.inputStream().use { copyStream(it, tos, progress, true) }
                    tos.closeArchiveEntry()
                }
            }
            files.forEach { add(it, it.name) }
            tos.finish()
        }
    }
}
