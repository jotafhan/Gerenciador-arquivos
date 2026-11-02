package com.example.filemanager

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Date

enum class ArchiveFormat(val label: String) {
    ZIP("ZIP"),
    SEVEN_Z("7z"),
    TAR_GZ("TAR.GZ")
}

/**
 * Compactar e extrair vários formatos.
 * Extrai: ZIP, 7z, RAR (só RAR4), TAR, TAR.GZ/TGZ, TAR.BZ2, TAR.XZ, GZ, BZ2, XZ.
 * Compacta: ZIP, 7z, TAR.GZ.
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

    // ------------------------------------------------------------ Extrair

    fun extract(archive: File, destDir: File): Result<File> = runCatching {
        val lower = archive.name.lowercase()
        if (lower.endsWith(".zip")) return@runCatching Zip.extract(archive, destDir).getOrThrow()

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
                IllegalStateException("Arquivo protegido por senha (não suportado)")
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

    fun compress(files: List<File>, destDir: File, format: ArchiveFormat): Result<File> = when (format) {
        ArchiveFormat.ZIP -> Zip.compress(files, destDir)
        ArchiveFormat.SEVEN_Z -> pack(files, destDir, "7z") { f, t -> write7z(f, t) }
        ArchiveFormat.TAR_GZ -> pack(files, destDir, "tar.gz") { f, t -> writeTarGz(f, t) }
    }

    private fun pack(
        files: List<File>,
        destDir: File,
        ext: String,
        writer: (List<File>, File) -> Unit
    ): Result<File> = runCatching {
        require(files.isNotEmpty()) { "Nada para compactar" }
        val first = files.first()
        val base = if (files.size == 1) {
            if (first.isDirectory) first.name else first.nameWithoutExtension
        } else {
            "Arquivos"
        }
        var target = File(destDir, "$base.$ext")
        var i = 1
        while (target.exists()) {
            target = File(destDir, "$base ($i).$ext")
            i++
        }
        try {
            writer(files, target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        target
    }

    private fun write7z(files: List<File>, target: File) {
        SevenZOutputFile(target).use { out ->
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

    private fun writeTarGz(files: List<File>, target: File) {
        val gz = GzipCompressorOutputStream(BufferedOutputStream(FileOutputStream(target)))
        TarArchiveOutputStream(gz).use { tos ->
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
