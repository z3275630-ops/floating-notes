package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor

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
    trashedCount: Int = 0,
    trashSelected: Boolean = false,
    onOpenTasks: (() -> Unit)? = null,
    onOpenTrash: (() -> Unit)? = null,
    onOpenStats: (() -> Unit)? = null,
    onOpenBackup: (() -> Unit)? = null,
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
            shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
            showBorder = false,
            modifier = Modifier
                .widthIn(max = (maxWidth - 52.dp).coerceIn(0.dp, 320.dp))
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
                contentPadding = WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                    .add(WindowInsets(left = 16.dp, top = 14.dp, right = 16.dp, bottom = 14.dp))
                    .asPaddingValues(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                        Text(
                            text = "文件夹",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$total 条笔记",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                        DrawerRow(
                            title = "全部笔记",
                            selected = allSelected,
                            dark = dark,
                            tag = "drawer-all",
                            icon = MintIcons.Records,
                            count = total,
                        ) { onRecord(false) }
                        if (onOpenTasks != null) {
                            MintGroupedDivider(dark = dark, startIndent = 46.dp)
                            DrawerRow(
                                title = "待办与提醒",
                                selected = false,
                                dark = dark,
                                tag = "drawer-tasks",
                                icon = MintIcons.Checklist,
                                count = pendingTasksCount.takeIf { it > 0 },
                                showChevron = true,
                                onClick = onOpenTasks,
                            )
                        }
                        MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        DrawerRow(
                            title = "归档",
                            selected = archiveSelected,
                            dark = dark,
                            tag = "drawer-archive",
                            icon = MintIcons.Archive,
                            count = archived,
                        ) { onRecord(true) }
                        if (onOpenTrash != null) {
                            MintGroupedDivider(dark = dark, startIndent = 46.dp)
                            DrawerRow(
                                title = "最近删除",
                                selected = trashSelected,
                                dark = dark,
                                tag = "drawer-trash",
                                icon = MintIcons.Trash,
                                count = trashedCount.takeIf { it > 0 },
                                showChevron = true,
                                onClick = onOpenTrash,
                            )
                        }
                        MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        DrawerRow(
                            title = "管理笔记本与标签",
                            selected = false,
                            dark = dark,
                            tag = "drawer-library",
                            icon = MintIcons.Library,
                            showChevron = true,
                        ) { onLibrary(LibraryFilter()) }
                    }
                }
                if (notebooks.isNotEmpty()) {
                    item(key = "drawer-notebooks-group") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DrawerHeading("笔记本")
                            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                                notebooks.forEachIndexed { index, notebook ->
                                    if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
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
                            }
                        }
                    }
                }
                if (sortedTags.isNotEmpty()) {
                    item(key = "drawer-tags-group") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DrawerHeading("标签")
                            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                                sortedTags.forEachIndexed { index, tag ->
                                    if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
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
                            }
                        }
                    }
                }
                if (onOpenStats != null || onOpenBackup != null) {
                    item(key = "drawer-backup-group") {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            if (onOpenStats != null) {
                                DrawerRow(
                                    title = "记录统计与热力图",
                                    selected = false,
                                    dark = dark,
                                    tag = "drawer-stats",
                                    icon = MintIcons.Chart,
                                    showChevron = true,
                                    onClick = onOpenStats,
                                )
                                if (onOpenBackup != null) {
                                    MintGroupedDivider(dark = dark, startIndent = 46.dp)
                                }
                            }
                            if (onOpenBackup != null) {
                                DrawerRow(
                                    title = "设置与数据管理",
                                    selected = false,
                                    dark = dark,
                                    tag = "drawer-backup",
                                    icon = MintIcons.Settings,
                                    showChevron = true,
                                    onClick = onOpenBackup,
                                )
                            }
                        }
                    }
                }
                if (labs != null || debugSampleSlot != null) {
                    item(key = "drawer-dev-group") {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("drawer-dev-section")
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                    ) { devExpanded = !devExpanded }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
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
                                        .background(mintSecondaryFill(dark))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
}

@Composable
private fun DrawerHeading(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
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
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val highlight = if (selected) {
        Modifier
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.primaryContainer)
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(highlight)
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = if (selected) 10.dp else 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = if (selected) scheme.primary else mintTertiaryTextColor(dark),
            )
        }
        if (showChevron) {
            Icon(
                imageVector = MintIcons.ChevronRight,
                contentDescription = null,
                tint = mintTertiaryTextColor(dark),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
