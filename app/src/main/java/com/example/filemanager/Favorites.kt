package com.example.filemanager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File

/** Pedido para abrir uma pasta (vem de um atalho na tela inicial do Android). */
object LaunchRequest {
    var path by mutableStateOf<String?>(null)
}

/** Lista de favoritos, guardada em SharedPreferences e observável pelo Compose. */
object Favorites {
    private const val PREFS = "favorites"
    private const val KEY = "paths"

    val items = mutableStateListOf<String>()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        items.addAll(raw.split('\n').filter { it.isNotBlank() })
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, items.joinToString("\n")).apply()
    }

    fun add(context: Context, files: List<File>) {
        files.forEach { if (it.absolutePath !in items) items.add(it.absolutePath) }
        save(context)
    }

    fun remove(context: Context, files: List<File>) {
        items.removeAll(files.map { it.absolutePath }.toSet())
        save(context)
    }

    /** Pede ao launcher um atalho na tela inicial do Android que abre a pasta (ou a pasta do arquivo). */
    fun pinShortcut(context: Context, file: File): String {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return "Este launcher não permite criar atalhos"
        }
        val target = if (file.isDirectory) file else (file.parentFile ?: file)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("open_path", target.absolutePath)
        }
        val icon = IconCompat.createWithBitmap(emojiBitmap("📁"))
        val info = ShortcutInfoCompat.Builder(context, "path_" + target.absolutePath.hashCode())
            .setShortLabel(target.name.take(24).ifEmpty { "Pasta" })
            .setLongLabel(target.name.ifEmpty { "Pasta" })
            .setIntent(intent)
            .setIcon(icon)
            .build()
        return if (ShortcutManagerCompat.requestPinShortcut(context, info, null)) {
            "Confirme o atalho na tela inicial"
        } else {
            "Não foi possível criar o atalho"
        }
    }

    private fun emojiBitmap(emoji: String): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A3F9F.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(emoji, size / 2f, y, text)
        return bitmap
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoritesScreen(storage: File, onClose: () -> Unit, onShowInFolder: (File) -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<File?>(null) }
    BackHandler { onClose() }

    val files = Favorites.items.map { File(it) }.filter { it.exists() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favoritos") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } }
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhum favorito ainda.\nSelecione itens e use ⋮ > Fixar.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(files, key = { it.absolutePath }) { f ->
                    FileRow(
                        file = f,
                        extra = f.parent?.removePrefix(storage.absolutePath),
                        selectionMode = false,
                        isSelected = false,
                        onClick = {
                            if (f.isDirectory) onShowInFolder(f) else FileOpener.open(context, f)
                        },
                        onLongClick = { picked = f }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    picked?.let { f ->
        ActionsDialog(
            title = f.name,
            info = f.absolutePath,
            actions = listOf(
                Act("Abrir") {
                    if (f.isDirectory) onShowInFolder(f) else FileOpener.open(context, f)
                },
                Act("Mostrar na pasta") { f.parentFile?.let { onShowInFolder(it) } },
                Act("Remover dos favoritos") { Favorites.remove(context, listOf(f)) }
            ),
            onDismiss = { picked = null }
        )
    }
}
