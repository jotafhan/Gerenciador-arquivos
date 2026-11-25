package com.example.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Estado guardado de uma guia do navegador. */
internal data class TabData(val path: String, val sort: String, val ascending: Boolean, val hidden: Boolean)

internal fun encodeTabs(list: List<TabData>): String =
    list.joinToString("\n") { "${it.path}\t${it.sort}\t${it.ascending}\t${it.hidden}" }

internal fun decodeTabs(raw: String): List<TabData> =
    raw.split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 4) TabData(p[0], p[1], p[2].toBoolean(), p[3].toBoolean()) else null
    }

/** Guias na barra do topo: ⌂ vai para a raiz, toque troca de guia, ✕ fecha, ＋ abre outra. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabStrip(
    titles: List<String>,
    active: Int,
    showClose: Boolean,
    onHome: () -> Unit,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            Text("⌂", modifier = Modifier.clickable(onClick = onHome).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        itemsIndexed(titles) { index, title ->
            val closeIcon: (@Composable () -> Unit)? = if (showClose) {
                { Text("✕", modifier = Modifier.clickable { onClose(index) }.padding(4.dp)) }
            } else {
                null
            }
            FilterChip(
                selected = index == active,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp)
                    )
                },
                trailingIcon = closeIcon
            )
        }
        item {
            Text("＋", modifier = Modifier.clickable(onClick = onNew).padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

/** Caminho clicável ( / > storage > emulated > 0 ) e, na ponta, quanto do armazenamento está usado. */
@Composable
internal fun BreadcrumbBar(
    path: String,
    rootPath: String,
    usedPercent: Int?,
    onNavigate: (String) -> Unit,
    onPillClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState(), reverseScrolling = true),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                Text("/", color = dim)
                var accumulated = ""
                path.split('/').filter { it.isNotEmpty() }.forEach { part ->
                    accumulated += "/$part"
                    val target = accumulated
                    val navigable = target.startsWith(rootPath)
                    Text("  >  ", color = dim)
                    Text(
                        part,
                        color = if (navigable) MaterialTheme.colorScheme.onSurface else dim,
                        modifier = Modifier
                            .clickable(enabled = navigable) { onNavigate(target) }
                            .padding(vertical = 8.dp)
                    )
                }
            }
            if (usedPercent != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onPillClick)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📊")
                    Text("  $usedPercent%", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
