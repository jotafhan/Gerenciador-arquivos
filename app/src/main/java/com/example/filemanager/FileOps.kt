package com.example.filemanager

import java.io.File

enum class SortKey(val label: String) {
    NAME("Nome"),
    DATE("Data"),
    SIZE("Tamanho")
}

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
