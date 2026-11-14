package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.NotebookEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagPath
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintHighlight

@OptIn(ExperimentalLayoutApi::class)
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
    notebookCounts: Map<String, Int> = emptyMap(),
    tagCounts: Map<String, Int> = emptyMap(),
    pendingTasksCount: Int = 0,
    onOpenTasks: (() -> Unit)? = null,
    labs: (@Composable RowScope.() -> Unit)? = null,
    debugSampleSlot: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    val sortedTags = remember(paths, tags) { tags.sortedBy { paths.getValue(it.id) } }
    var devExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxHeight()) {
        GlassSurface(
            backdrop = backdrop,
            tier = GlassTier.Enhanced,
            dark = dark,
            elevatedModal = true,
            shapeRadius = 28.dp,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                .widthIn(max = (maxWidth - 48.dp).coerceIn(0.dp, 324.dp))
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag("notes-drawer")
                .semantics { paneTitle = "笔记导航" },
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("notes-drawer-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                        Text(
                            text = "笔记导航",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$total 条未归档笔记",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    DrawerRow(
                        title = "全部笔记",
                        selected = allSelected,
                        dark = dark,
                        tag = "drawer-all",
                        icon = MintIcons.Records,
                        count = total,
                    ) { onRecord(false) }
                }
                if (onOpenTasks != null) {
                    item {
                        DrawerRow(
                            title = "待办与提醒",
                            selected = false,
                            dark = dark,
                            tag = "drawer-tasks",
                            icon = MintIcons.Checklist,
                            count = pendingTasksCount.takeIf { it > 0 },
                            onClick = onOpenTasks,
                        )
                    }
                }
                item {
                    DrawerRow(
                        title = "归档 · $archived",
                        selected = archiveSelected,
                        dark = dark,
                        tag = "drawer-archive",
                        icon = MintIcons.Archive,
                    ) { onRecord(true) }
                }
                item {
                    DrawerRow(
                        title = "管理笔记本与标签",
                        selected = false,
                        dark = dark,
                        tag = "drawer-library",
                        icon = MintIcons.Library,
                    ) { onLibrary(LibraryFilter()) }
                }
                if (notebooks.isNotEmpty()) item { DrawerHeading("笔记本") }
                items(notebooks, key = { "notebook:${it.id}" }) { notebook ->
                    DrawerRow(
                        title = notebook.name,
                        selected = filter.notebook == notebook.id,
                        dark = dark,
                        tag = "drawer-notebook",
                        icon = MintIcons.Folder,
                        count = notebookCounts[notebook.id],
                    ) {
                        onLibrary(LibraryFilter(notebook = notebook.id))
                    }
                }
                if (tags.isNotEmpty()) item { DrawerHeading("标签") }
                items(sortedTags, key = { "tag:${it.id}" }) { tag ->
                    DrawerRow(
                        title = paths.getValue(tag.id),
                        selected = filter.tag == tag.id,
                        dark = dark,
                        tag = "drawer-tag",
                        icon = MintIcons.Tag,
                        count = tagCounts[tag.id],
                    ) {
                        onLibrary(LibraryFilter(tag = tag.id))
                    }
                }
                if (labs != null || debugSampleSlot != null) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = scheme.outline.copy(alpha = 0.16f))
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .testTag("drawer-dev-section")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { devExpanded = !devExpanded }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = MintIcons.DevTools,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "开发与实验",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (devExpanded) MintIcons.ChevronDown else MintIcons.ChevronRight,
                                contentDescription = if (devExpanded) "收起" else "展开",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        AnimatedVisibility(visible = devExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (dark) Color(0xFF192921) else Color(0xFFF1F7F3))
                                    .border(
                                        0.8.dp,
                                        scheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(16.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (labs != null) {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        content = labs,
                                    )
                                }
                                debugSampleSlot?.invoke()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeading(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerRow(
    title: String,
    selected: Boolean,
    dark: Boolean,
    tag: String,
    icon: ImageVector,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val highlight = if (selected) {
        Modifier
            .background(mintHighlight(dark), shape)
            .border(1.dp, scheme.primary.copy(alpha = if (dark) 0.45f else 0.25f), shape)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .then(highlight)
            .testTag(tag)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (selected) scheme.onSurface else scheme.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) scheme.surface.copy(alpha = 0.65f)
                        else scheme.onSurfaceVariant.copy(alpha = 0.10f),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}
