package com.example.filemanager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Instala APKs e pacotes de APKs (.apks, .xapk, .apkm) com o instalador do Android. */
object ApkInstaller {
    private val BUNDLE_EXTENSIONS = listOf(".apks", ".xapk", ".apkm")

    fun isApk(file: File): Boolean = file.extension.equals("apk", ignoreCase = true)

    fun isBundle(file: File): Boolean {
        val name = file.name.lowercase()
        return BUNDLE_EXTENSIONS.any { name.endsWith(it) }
    }

    fun isInstallable(file: File): Boolean = isApk(file) || isBundle(file)

    private fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Inicia a instalação. Devolve uma mensagem para mostrar ao usuário (ou null). */
    fun install(context: Context, file: File): String? {
        if (!canInstall(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            }
            return "Permita instalar apps por este gerenciador e toque em Instalar de novo"
        }
        if (!isBundle(file)) {
            FileOpener.open(context, file)
            return null
        }
        val app = context.applicationContext
        Thread {
            val error = runCatching { commitSession(app, file) }.exceptionOrNull()
            if (error != null) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, "Falha ao instalar: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
        return "Preparando a instalação..."
    }

    /** Quantos APKs existem dentro de um pacote. */
    fun apkCount(file: File): Int = runCatching {
        ZipFile(file).use { zf -> zf.entries().toList().count { !it.isDirectory && it.name.lowercase().endsWith(".apk") } }
    }.getOrDefault(0)

    private fun commitSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            var count = 0
            ZipFile(file).use { zf ->
                for (entry in zf.entries().toList()) {
                    if (entry.isDirectory || !entry.name.lowercase().endsWith(".apk")) continue
                    zf.getInputStream(entry).use { input ->
                        val size = if (entry.size > 0) entry.size else -1L
                        session.openWrite("part${count++}.apk", 0, size).use { out ->
                            input.copyTo(out, 256 * 1024)
                            session.fsync(out)
                        }
                    }
                }
            }
            if (count == 0) error("Nenhum APK encontrado dentro do arquivo")
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Recebe o resultado da instalação e abre a tela de confirmação do Android. */
class InstallResultReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "App instalado", Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "motivo desconhecido"
                Toast.makeText(context, "Instalação não concluída: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION = "com.example.filemanager.INSTALL_RESULT"
    }
}

/** Pergunta se o usuário quer instalar o APK (ou pacote de APKs). */
@Composable
internal fun InstallApkDialog(
    file: File,
    onDismiss: () -> Unit,
    onViewContent: ((File) -> Unit)?,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val bundle = ApkInstaller.isBundle(file)
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(file) {
        rows = withContext(Dispatchers.IO) {
            if (bundle) {
                listOf("Contém" to "${ApkInstaller.apkCount(file)} APK(s)")
            } else {
                MetadataReader.read(context, file)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bundle) "Instalar pacote de apps?" else "Instalar aplicativo?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val list = rows
                if (list == null) {
                    Text("Lendo informações...")
                } else {
                    list.forEach { (label, value) -> Text("$label: $value") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                ApkInstaller.install(context, file)?.let(onResult)
            }) { Text("Instalar") }
        },
        dismissButton = {
            Row {
                if (onViewContent != null) {
                    TextButton(onClick = { onViewContent(file) }) { Text("Ver conteúdo") }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}
