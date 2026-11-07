package com.example.filemanager

import java.io.File

enum class SortKey(val label: String) {
    NAME("Nome"),
    DATE("Data"),
    SIZE("Tamanho")
}

/** Tipo, tamanho e data de um item, lidos uma vez só (cada consulta ao armazenamento é lenta). */
data class FileEntry(val file: File, val isDir: Boolean, val size: Long, val modified: Long)

/** Operações de arquivo (sem interface). Rodam fora da thread principal. */
object FileOps {

    fun list(dir: File, showHidden: Boolean): List<File> =
        dir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?: emptyList()

    /**
     * Pastas primeiro, depois pelo critério escolhido. Ao ordenar por tamanho, pastas e arquivos
     * ficam misturados; o tamanho de cada pasta vem de [dirSizes] (caminho -> bytes), calculado à parte.
     */
    fun sort(
        list: List<File>,
        key: SortKey,
        ascending: Boolean,
        dirSizes: Map<String, Long> = emptyMap()
    ): List<File> {
        val byKey: Comparator<File> = when (key) {
            SortKey.NAME -> compareBy<File> { it.name.lowercase() }
            SortKey.DATE -> compareBy<File> { it.lastModified() }
            SortKey.SIZE -> compareBy<File> {
                if (it.isDirectory) (dirSizes[it.absolutePath] ?: 0L) else it.length()
            }
        }
        val ordered = if (ascending) byKey else byKey.reversed()
        val full = if (key == SortKey.SIZE) ordered else compareByDescending<File> { it.isDirectory }.then(ordered)
        return list.sortedWith(full)
    }

    /** Gera um nome livre no destino: "foto.jpg" vira "foto (1).jpg" se já existir. */
    fun uniqueTarget(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }

    /** true se [child] é a própria [parent] ou está dentro dela. */
    fun isInside(parent: File, child: File): Boolean {
        val p = parent.canonicalFile
        var c: File? = child.canonicalFile
        while (c != null) {
            if (c == p) return true
            c = c.parentFile
        }
        return false
    }

    fun copy(src: File, destDir: File): Result<File> = runCatching {
        if (src.isDirectory && isInside(src, destDir)) {
            error("Não dá para copiar uma pasta para dentro dela mesma")
        }
        val target = uniqueTarget(destDir, src.name)
        src.copyRecursively(target, overwrite = false)
        target
    }

    fun move(src: File, destDir: File): Result<File> = runCatching {
        if (src.isDirectory && isInside(src, destDir)) {
            error("Não dá para mover uma pasta para dentro dela mesma")
        }
        if (src.parentFile?.canonicalFile == destDir.canonicalFile) {
            return@runCatching src // já está nessa pasta
        }
        val target = uniqueTarget(destDir, src.name)
        if (!src.renameTo(target)) {
            // renameTo falha entre volumes diferentes: copia e apaga o original
            src.copyRecursively(target, overwrite = false)
            if (!src.deleteRecursively()) error("Copiou, mas não conseguiu apagar o original")
        }
        target
    }

    fun rename(file: File, newName: String): Result<File> = runCatching {
        val clean = newName.trim()
        require(clean.isNotEmpty()) { "O nome não pode ficar vazio" }
        require('/' !in clean) { "O nome não pode ter /" }
        val target = File(file.parentFile, clean)
        if (target.exists()) error("Já existe um item com esse nome")
        if (!file.renameTo(target)) error("Não foi possível renomear")
        target
    }

    /** Move tudo que está dentro de [dir] para a pasta acima e remove [dir] se ficar vazia. */
    fun moveContentUp(dir: File): Result<Unit> = runCatching {
        val parent = dir.parentFile ?: error("Esta pasta não tem uma pasta acima")
        val children = dir.listFiles() ?: error("Sem acesso a esta pasta")
        val errors = ArrayList<String>()
        for (child in children) {
            move(child, parent).onFailure { errors.add("${child.name}: ${it.message}") }
        }
        if (errors.isNotEmpty()) error(errors.first())
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
        Unit
    }

    /** Lê tipo, tamanho e data numa única chamada ao sistema. */
    fun entryOf(file: File): FileEntry =
        try {
            val attrs = java.nio.file.Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java
            )
            val dir = attrs.isDirectory
            FileEntry(file, dir, if (dir) 0L else attrs.size(), attrs.lastModifiedTime().toMillis())
        } catch (e: Exception) {
            FileEntry(file, file.isDirectory, file.length(), file.lastModified())
        }

    fun listEntries(dir: File, showHidden: Boolean): List<FileEntry> =
        (dir.listFiles() ?: emptyArray())
            .filter { showHidden || !it.name.startsWith(".") }
            .map { entryOf(it) }

    /** Mesma ordenação de [sort], mas sem consultar o armazenamento (usa os dados já lidos). */
    fun sortEntries(
        list: List<FileEntry>,
        key: SortKey,
        ascending: Boolean,
        dirSizes: Map<String, Long> = emptyMap()
    ): List<FileEntry> {
        val byKey: Comparator<FileEntry> = when (key) {
            SortKey.NAME -> compareBy<FileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            SortKey.DATE -> compareBy<FileEntry> { it.modified }
            SortKey.SIZE -> compareBy<FileEntry> {
                if (it.isDir) (dirSizes[it.file.absolutePath] ?: 0L) else it.size
            }
        }
        val ordered = if (ascending) byKey else byKey.reversed()
        val full = if (key == SortKey.SIZE) ordered else compareByDescending<FileEntry> { it.isDir }.then(ordered)
        return list.sortedWith(full)
    }

    fun createFile(parent: File, name: String): Result<File> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "O nome não pode ficar vazio" }
        require('/' !in clean) { "O nome não pode ter /" }
        val target = File(parent, clean)
        if (target.exists()) error("Já existe um item com esse nome")
        if (!target.createNewFile()) error("Não foi possível criar o arquivo")
        target
    }

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "O nome não pode ficar vazio" }
        require('/' !in clean) { "O nome não pode ter /" }
        val target = File(parent, clean)
        if (target.exists()) error("Já existe um item com esse nome")
        if (!target.mkdirs()) error("Não foi possível criar a pasta")
        target
    }

    /** Busca recursiva pelo nome (até [limit] resultados e 12 níveis de profundidade). */
    fun search(root: File, query: String, showHidden: Boolean, limit: Int = 500): List<File> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<File>()
        val walk = root.walkTopDown()
            .maxDepth(12)
            .onEnter { dir -> dir == root || showHidden || !dir.name.startsWith(".") }
        for (f in walk) {
            if (f == root) continue
            if (!showHidden && f.name.startsWith(".")) continue
            if (f.name.lowercase().contains(q)) {
                out.add(f)
                if (out.size >= limit) break
            }
        }
        return out
    }
}
