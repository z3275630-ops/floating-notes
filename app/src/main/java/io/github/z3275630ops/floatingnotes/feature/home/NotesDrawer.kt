package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.NotebookEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagPath
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintHighlight

@Composable
internal fun NotesDrawer(
    backdrop: GlassBackdrop,
    dark: Boolean,
    total: Int,
    archived: Int,
    archiveSelected: Boolean,
    allSelected: Boolean,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    filter: LibraryFilter,
    onRecord: (Boolean) -> Unit,
    onLibrary: (LibraryFilter) -> Unit,
) {
    val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    val sortedTags = remember(paths, tags) { tags.sortedBy { paths.getValue(it.id) } }
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        GlassSurface(
            backdrop, GlassTier.Enhanced, dark,
            Modifier.safeDrawingPadding().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                .widthIn(max = (maxWidth - 48.dp).coerceIn(0.dp, 320.dp)).fillMaxWidth().fillMaxHeight()
                .testTag("notes-drawer").semantics { paneTitle = "笔记导航" },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp)) {
                        Text("笔记导航", style = MaterialTheme.typography.titleLarge)
                        Text("$total 条未归档笔记", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    DrawerRow("全部笔记", allSelected, dark, "drawer-all") { onRecord(false) }
                }
                item {
                    DrawerRow("归档 · $archived", archiveSelected, dark, "drawer-archive") { onRecord(true) }
                }
                item {
                    DrawerRow("管理笔记本与标签", false, dark, "drawer-library") { onLibrary(LibraryFilter()) }
                }
                if (notebooks.isNotEmpty()) item { DrawerHeading("笔记本") }
                items(notebooks, key = { "notebook:${it.id}" }) { notebook ->
                    DrawerRow(notebook.name, filter.notebook == notebook.id, dark, "drawer-notebook") {
                        onLibrary(LibraryFilter(notebook = notebook.id))
                    }
                }
                if (tags.isNotEmpty()) item { DrawerHeading("标签") }
                items(sortedTags, key = { "tag:${it.id}" }) { tag ->
                    DrawerRow(paths.getValue(tag.id), filter.tag == tag.id, dark, "drawer-tag") {
                        onLibrary(LibraryFilter(tag = tag.id))
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeading(title: String) {
    Text(title, Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DrawerRow(title: String, selected: Boolean, dark: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val highlight = if (selected) Modifier.background(mintHighlight(dark), shape) else Modifier
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).then(highlight)
            .selectable(selected = selected, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}
