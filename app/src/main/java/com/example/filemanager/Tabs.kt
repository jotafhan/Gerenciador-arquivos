package com.example.filemanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** Faixa de guias: toque troca de guia, ✕ fecha, ➕ abre uma cópia da pasta atual. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabStrip(
    titles: List<String>,
    active: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onNew: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(titles) { index, title ->
            FilterChip(
                selected = index == active,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                },
                trailingIcon = {
                    Text("✕", modifier = Modifier.clickable { onClose(index) }.padding(4.dp))
                }
            )
        }
        item {
            AssistChip(onClick = onNew, label = { Text("➕") })
        }
    }
}
