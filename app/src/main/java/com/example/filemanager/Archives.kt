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
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

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

/**
 * Compactar e extrair vários formatos.
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

    private fun baseNameOf(file: File): String {
        val lower = file.name.lowercase()
        val ext = EXTENSIONS.firstOrNull { lower.endsWith(it) }
        val base = if (ext != null) file.name.dropLast(ext.length) else file.name.substringBeforeLast('.')
        return base.ifEmpty { "extraido" }
    }

    private fun uniqueTarget(dir: File, base: String, ext: String): File {
        var target = File(dir, "$base.$ext")
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i).$ext")
            i++
        }
        return target
    }

    // ------------------------------------------------------------ Extrair

    private fun isZipEncrypted(file: File): Boolean =
        runCatching { ZipFile(file).isEncrypted }.getOrDefault(false)

    private fun hasSplitParts(zip: File): Boolean =
        File(zip.parentFile, zip.name.dropLast(4) + ".z01").exists()

    fun extract(archive: File, destDir: File, password: String? = null): Result<File> = runCatching {
        val lower = archive.name.lowercase()

        if (lower.endsWith(".zip")) {
            val encrypted = isZipEncrypted(archive)
            if (!encrypted && password == null && !hasSplitParts(archive)) {
                return@runCatching Zip.extract(archive, destDir).getOrThrow()
            }
            if (encrypted && password.isNullOrEmpty()) throw PasswordRequiredException(false)
            val target = FileOps.uniqueTarget(destDir, baseNameOf(archive))
            if (!target.mkdirs()) error("Não foi possível criar a pasta de destino")
            try {
                val zip = if (password.isNullOrEmpty()) ZipFile(archive) else ZipFile(archive, password.toCharArray())
                zip.use { it.extractAll(target.absolutePath) }
            } catch (e: ZipException) {
                target.deleteRecursively()
                if (e.type == ZipException.Type.WRONG_PASSWORD) throw PasswordRequiredException(true)
                throw e
            } catch (e: Exception) {
                target.deleteRecursively()
                throw e
            }
            return@runCatching target
        }

        val target = FileOps.uniqueTarget(destDir, baseNameOf(archive))
        if (!target.mkdirs()) error("Não foi possível criar a pasta de destino")
        try {
            when {
                lower.endsWith(".7z") -> extract7z(archive, target)
                lower.endsWith(".rar") -> extractRar(archive, target)
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                    extractTar(archive, target) { GzipCompressorInputStream(it) }
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ->
                    extractTar(archive, target) { BZip2CompressorInputStream(it) }
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") ->
                    extractTar(archive, target) { XZCompressorInputStream(it) }
                lower.endsWith(".tar") -> extractTar(archive, target) { it }
                lower.endsWith(".gz") -> extractSingle(archive, target, ".gz") { GzipCompressorInputStream(it) }
                lower.endsWith(".bz2") -> extractSingle(archive, target, ".bz2") { BZip2CompressorInputStream(it) }
                lower.endsWith(".xz") -> extractSingle(archive, target, ".xz") { XZCompressorInputStream(it) }
                else -> error("Formato não suportado")
            }
        } catch (e: Exception) {
            target.deleteRecursively()
            throw friendly(e)
        }
        target
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

    private fun extract7z(archive: File, target: File) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            val buf = ByteArray(64 * 1024)
            var entry = sz.nextEntry
            while (entry != null) {
                val out = resolveSafe(target, entry.name)
                if (out != null) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { os ->
                            while (true) {
                                val n = sz.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                            }
                        }
                    }
                }
                entry = sz.nextEntry
            }
        }
    }

    private fun extractRar(archive: File, target: File) {
        Archive(archive).use { rar ->
            var header = rar.nextFileHeader()
            while (header != null) {
                val h = header
                val out = resolveSafe(target, h.fileName)
                if (out != null) {
                    if (h.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { os -> rar.extractFile(h, os) }
                    }
                }
                header = rar.nextFileHeader()
            }
        }
    }

    private fun extractTar(archive: File, target: File, wrap: (InputStream) -> InputStream) {
        val input = wrap(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(input).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                val out = resolveSafe(target, entry.name)
                if (out != null) {
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tis.copyTo(it) }
                    }
                }
                entry = tis.nextEntry
            }
        }
    }

    private fun extractSingle(archive: File, target: File, ext: String, wrap: (InputStream) -> InputStream) {
        val name = archive.name.dropLast(ext.length).ifEmpty { "arquivo" }
        wrap(BufferedInputStream(FileInputStream(archive))).use { input ->
            FileOutputStream(File(target, name)).use { input.copyTo(it) }
        }
    }

    // ------------------------------------------------------------ Compactar

    /** Cria o(s) arquivo(s) compactado(s) em [destDir]. Devolve os arquivos criados. */
    fun compress(
        files: List<File>,
        destDir: File,
        baseName: String,
        options: CompressOptions
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
            try {
                write(group, target, options)
            } catch (e: Exception) {
                cleanup(target, options)
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

    private fun write(files: List<File>, target: File, o: CompressOptions) {
        when (o.format) {
            ArchiveFormat.ZIP -> writeZip(files, target, o)
            ArchiveFormat.SEVEN_Z -> write7z(files, target, o.level)
            ArchiveFormat.TAR -> writeTar(files, target) { it }
            ArchiveFormat.TAR_GZ -> writeTar(files, target) {
                GzipCompressorOutputStream(it, GzipParameters().apply { compressionLevel = gzLevel(o.level) })
            }
            ArchiveFormat.TAR_BZ2 -> writeTar(files, target) { BZip2CompressorOutputStream(it, bzBlock(o.level)) }
            ArchiveFormat.TAR_XZ -> writeTar(files, target) { XZCompressorOutputStream(it, xzPreset(o.level)) }
        }
    }

    private fun writeZip(files: List<File>, target: File, o: CompressOptions) {
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
        zip.use { z ->
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

    private fun write7z(files: List<File>, target: File, level: LevelOption) {
        SevenZOutputFile(target).use { out ->
            if (level == LevelOption.STORE) {
                out.setContentCompression(SevenZMethod.COPY)
            } else {
                out.setContentMethods(
                    listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(xzPreset(level))))
                )
            }
            val buf = ByteArray(64 * 1024)
            fun add(file: File, path: String) {
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
                    file.inputStream().use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.closeArchiveEntry()
                }
            }
            files.forEach { add(it, it.name) }
            out.finish()
        }
    }

    private fun writeTar(files: List<File>, target: File, wrap: (OutputStream) -> OutputStream) {
        val raw = BufferedOutputStream(FileOutputStream(target))
        TarArchiveOutputStream(wrap(raw)).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            fun add(file: File, path: String) {
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
                    file.inputStream().use { it.copyTo(tos) }
                    tos.closeArchiveEntry()
                }
            }
            files.forEach { add(it, it.name) }
            tos.finish()
        }
    }
}
