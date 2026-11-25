package com.example.filemanager

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/** Opções do app. Os valores que mudam a tela são estados do Compose. */
object AppSettings {
    private const val PREFS = "ui"

    var showThumbnails by mutableStateOf(true)
    var confirmInstall by mutableStateOf(true)
    var gridMode by mutableStateOf(false)
    var showHiddenDefault = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        showThumbnails = prefs.getBoolean("show_thumbnails", true)
        confirmInstall = prefs.getBoolean("confirm_install", true)
        gridMode = prefs.getBoolean("grid_mode", false)
        showHiddenDefault = prefs.getBoolean("show_hidden_default", false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var hiddenDefault by remember { mutableStateOf(AppSettings.showHiddenDefault) }
    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            SettingsSection("Aparência")
            SwitchRow(
                title = "Visualização em grade",
                subtitle = "Mostra os arquivos como ícones grandes em vez de lista",
                checked = AppSettings.gridMode
            ) {
                AppSettings.gridMode = it
                prefs.edit().putBoolean("grid_mode", it).apply()
            }
            SwitchRow(
                title = "Mostrar miniaturas",
                subtitle = "Fotos, vídeos, PDFs e ícones de APK. Desligar deixa a rolagem mais leve",
                checked = AppSettings.showThumbnails
            ) {
                AppSettings.showThumbnails = it
                prefs.edit().putBoolean("show_thumbnails", it).apply()
            }
            SwitchRow(
                title = "Mostrar arquivos ocultos por padrão",
                subtitle = "Vale para as pastas abertas a partir de agora",
                checked = hiddenDefault
            ) {
                hiddenDefault = it
                AppSettings.showHiddenDefault = it
                prefs.edit().putBoolean("show_hidden_default", it).apply()
            }

            SettingsSection("Instalação")
            SwitchRow(
                title = "Perguntar antes de instalar APK",
                subtitle = "Mostra os dados do app antes de abrir o instalador",
                checked = AppSettings.confirmInstall
            ) {
                AppSettings.confirmInstall = it
                prefs.edit().putBoolean("confirm_install", it).apply()
            }

            SettingsSection("Manutenção")
            ActionRow(
                title = "Limpar cache",
                subtitle = "Apaga miniaturas guardadas e arquivos abertos de dentro de compactados"
            ) {
                Thumbnails.clear()
                File(context.cacheDir, "archive_preview").deleteRecursively()
                Toast.makeText(context, "Cache limpo", Toast.LENGTH_SHORT).show()
            }

            SettingsSection("Sobre")
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text(
                text = "Gerenciador de arquivos  •  versão ${version ?: "?"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
