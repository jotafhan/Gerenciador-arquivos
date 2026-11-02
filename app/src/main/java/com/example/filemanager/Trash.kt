package com.example.filemanager

import java.io.File

/**
 * Lixeira própria do app. Cada item apagado vai para uma pasta própria dentro de
 * ".lixeira_gerenciador" (na raiz do armazenamento), junto de um arquivo ".origem"
 * que guarda o caminho original para permitir restaurar.
 */
object Trash {
    private const val DIR_NAME = ".lixeira_gerenciador"
    private const val ORIGIN = ".origem"

    data class Entry(val dir: File, val item: File, val origin: String, val deletedAt: Long)

    fun root(storage: File): File = File(storage, DIR_NAME)

    fun isInTrash(storage: File, file: File): Boolean =
        runCatching { FileOps.isInside(root(storage), file) }.getOrDefault(false)

    fun moveToTrash(storage: File, file: File): Result<Unit> = runCatching {
        val trash = root(storage)
        if (!trash.exists() && !trash.mkdirs()) error("Não foi possível criar a lixeira")
        // Evita que a galeria e os players mostrem o conteúdo da lixeira
        File(trash, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        val dir = File(trash, "${System.currentTimeMillis()}_${(1000..9999).random()}")
        if (!dir.mkdirs()) error("Não foi possível criar a pasta da lixeira")
        try {
            File(dir, ORIGIN).writeText(file.absolutePath)
            FileOps.move(file, dir).getOrThrow()
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        Unit
    }

    fun list(storage: File): List<Entry> {
        val dirs = root(storage).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val originFile = File(dir, ORIGIN)
            if (!originFile.exists()) return@mapNotNull null
            val item = dir.listFiles()?.firstOrNull { it.name != ORIGIN } ?: return@mapNotNull null
            Entry(
                dir = dir,
                item = item,
                origin = originFile.readText(),
                deletedAt = dir.name.substringBefore('_').toLongOrNull() ?: dir.lastModified()
            )
        }.sortedByDescending { it.deletedAt }
    }

    fun restore(entry: Entry): Result<File> = runCatching {
        val destDir = File(entry.origin).parentFile ?: error("Local original inválido")
        if (!destDir.exists() && !destDir.mkdirs()) error("Não foi possível recriar a pasta original")
        val restored = FileOps.move(entry.item, destDir).getOrThrow()
        entry.dir.deleteRecursively()
        restored
    }

    fun purge(entry: Entry): Boolean = entry.dir.deleteRecursively()

    fun empty(storage: File): Boolean {
        var ok = true
        root(storage).listFiles()?.filter { it.isDirectory }?.forEach {
            if (!it.deleteRecursively()) ok = false
        }
        return ok
    }
}
