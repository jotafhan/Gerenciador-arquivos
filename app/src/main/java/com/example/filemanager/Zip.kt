package com.example.filemanager

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Compactar e extrair ZIP usando só a biblioteca padrão do Java/Android. */
object Zip {

    fun compress(files: List<File>, destDir: File): Result<File> = runCatching {
        require(files.isNotEmpty()) { "Nada para compactar" }
        val first = files.first()
        val base = if (files.size == 1) {
            if (first.isDirectory) first.name else first.nameWithoutExtension
        } else {
            "Arquivos"
        }
        val target = FileOps.uniqueTarget(destDir, "$base.zip")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zos ->
                files.forEach { addEntry(zos, it, it.name) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        target
    }

    private fun addEntry(zos: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.isEmpty()) {
                zos.putNextEntry(ZipEntry("$path/"))
                zos.closeEntry()
            }
            for (child in children) addEntry(zos, child, "$path/${child.name}")
        } else {
            val entry = ZipEntry(path)
            entry.time = file.lastModified()
            zos.putNextEntry(entry)
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }

    /** Extrai para uma pasta nova com o nome do ZIP, ao lado dele. */
    fun extract(zip: File, destDir: File): Result<File> = runCatching {
        val target = FileOps.uniqueTarget(destDir, zip.nameWithoutExtension)
        if (!target.mkdirs()) error("Não foi possível criar a pasta de destino")
        val basePath = target.canonicalPath + File.separator
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val out = File(target, entry.name)
                    // Proteção contra ZIPs maliciosos que tentam escrever fora da pasta
                    if (!out.canonicalPath.startsWith(basePath)) {
                        error("ZIP inválido: caminho fora da pasta de destino")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
        target
    }
}
