package com.example.filemanager

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Abre ou compartilha arquivos com outros apps. */
object FileOpener {

    fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "*/*"

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun open(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(context, file), mimeOf(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app para abrir este tipo de arquivo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Abre sempre mostrando a lista de apps (ignora o app padrão). */
    fun openWith(context: Context, file: File) {
        try {
            val uri = uriFor(context, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("", uri)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir com"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Nenhum app para abrir este tipo de arquivo", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Compartilha arquivos (pastas não são aceitas: o chamador deve filtrar). */
    fun share(context: Context, files: List<File>) {
        val list = files.filter { it.isFile }
        if (list.isEmpty()) return
        try {
            val uris = ArrayList(list.map { uriFor(context, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(list.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Compartilhar"))
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível compartilhar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
