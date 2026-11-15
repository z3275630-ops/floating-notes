package io.github.z3275630ops.floatingnotes.feature.home

import android.database.sqlite.SQLiteConstraintException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenuItem
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSelectionRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.NoteMenuTrigger
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintHighlight
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class LibraryFilter(
    val tag: String? = null,
    val notebook: String? = null,
    val descendants: Boolean = true,
)

private data class OrganizationAction(
    val title: String,
    val initial: String? = null,
    val choices: List<Pair<String, String>> = emptyList(),
    val destructive: Boolean = false,
    val confirm: suspend (String) -> Unit,
)

@Composable
internal fun OrganizationPanel(
    repository: OrganizationRepository,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    notebookCounts: Map<String, Int> = emptyMap(),
    tagCounts: Map<String, Int> = emptyMap(),
    dark: Boolean = isSystemInDarkTheme(),
) {
    val scheme = MaterialTheme.colorScheme
    var action by remember { mutableStateOf<OrganizationAction?>(null) }
    val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    val sortedTags = remember(paths, tags) { tags.sortedBy { paths.getValue(it.id) } }
    var manageMenu by remember { mutableStateOf(false) }
    var notebooksExpandedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var tagsExpandedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val notebooksExpanded = notebooksExpandedOverride ?: (notebooks.size <= 4)
    val tagsExpanded = tagsExpandedOverride ?: (sortedTags.size <= 6)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "笔记本与标签",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Box {
                NoteMenuTrigger(
                    label = "管理笔记本与标签",
                    tag = "library-organization-actions",
                    containerColor = mintSecondaryFill(dark),
                    onClick = { manageMenu = true },
                )
                ComposablesMenu(expanded = manageMenu, onDismiss = { manageMenu = false }) {
                    ComposablesMenuItem("新建笔记本", icon = MintIcons.Folder) {
                        manageMenu = false
                        action = OrganizationAction("新建笔记本", "") { repository.createNotebook(it) }
                    }
                    ComposablesMenuItem("新建标签", icon = MintIcons.Tag) {
                        manageMenu = false
                        action = OrganizationAction("新建标签", "") { repository.createTag(it) }
                    }
                    if (filter.tag != null) {
                        ComposablesMenuItem(
                            label = if (filter.descendants) "仅当前标签" else "包含下级标签",
                            icon = MintIcons.Filter,
                        ) {
                            manageMenu = false
                            onFilter(filter.copy(descendants = !filter.descendants))
                        }
                    }
                }
            }
        }

        if (notebooks.isEmpty() && sortedTags.isEmpty()) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Text(
                    text = "还没有笔记本或标签，点击右上角可快速新建。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (notebooks.isNotEmpty()) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                OrganizationSectionHeader(
                    title = "笔记本",
                    count = notebooks.size,
                    icon = MintIcons.Folder,
                    dark = dark,
                    expanded = notebooksExpanded,
                    onToggle = { notebooksExpandedOverride = !notebooksExpanded },
                )
                AnimatedVisibility(visible = notebooksExpanded) {
                    Column {
                        MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        notebooks.forEachIndexed { index, notebook ->
                            if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
                            var menu by remember(notebook.id) { mutableStateOf(false) }
                            val selected = filter.notebook == notebook.id
                            Box {
                                OrganizationGroupRow(
                                    label = notebook.name,
                                    count = notebookCounts[notebook.id],
                                    selected = selected,
                                    icon = MintIcons.Folder,
                                    dark = dark,
                                    menuTag = "notebook-actions",
                                    menuDescription = "管理笔记本 ${notebook.name}",
                                    onClick = {
                                        onFilter(filter.copy(notebook = notebook.id.takeUnless { it == filter.notebook }))
                                    },
                                    onMenuClick = { menu = true },
                                )
                                ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                                    ComposablesMenuItem("重命名", icon = MintIcons.Folder) {
                                        menu = false
                                        action = OrganizationAction("重命名笔记本", notebook.name) {
                                            repository.renameNotebook(notebook.id, it)
                                        }
                                    }
                                    ComposablesMenuItem("删除笔记本", icon = MintIcons.Trash, destructive = true) {
                                        menu = false
                                        action = OrganizationAction("删除笔记本「${notebook.name}」？", destructive = true) {
                                            repository.deleteNotebook(notebook.id)
                                            if (filter.notebook == notebook.id) onFilter(filter.copy(notebook = null))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (sortedTags.isNotEmpty()) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                OrganizationSectionHeader(
                    title = "标签",
                    count = sortedTags.size,
                    icon = MintIcons.Tag,
                    dark = dark,
                    expanded = tagsExpanded,
                    onToggle = { tagsExpandedOverride = !tagsExpanded },
                )
                AnimatedVisibility(visible = tagsExpanded) {
                    Column {
                        MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        sortedTags.forEachIndexed { index, tag ->
                            if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
                            val name = paths.getValue(tag.id)
                            var menu by remember(tag.id) { mutableStateOf(false) }
                            val selected = filter.tag == tag.id
                            Box {
                                OrganizationGroupRow(
                                    label = name,
                                    count = tagCounts[tag.id],
                                    selected = selected,
                                    icon = MintIcons.Tag,
                                    dark = dark,
                                    menuTag = "tag-actions",
                                    menuDescription = "管理标签 $name",
                                    onClick = {
                                        onFilter(filter.copy(tag = tag.id.takeUnless { it == filter.tag }))
                                    },
                                    onMenuClick = { menu = true },
                                )
                                ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                                    fun select(option: OrganizationAction) {
                                        menu = false
                                        action = option
                                    }
                                    val available = tags.filterNot { it.id in tagSubtree(tags, tag.id) }
                                        .map { it.id to paths.getValue(it.id) }
                                    ComposablesMenuItem("添加下级", icon = MintIcons.Plus) {
                                        select(OrganizationAction("新建下级标签", "") { repository.createTag(it, tag.id) })
                                    }
                                    ComposablesMenuItem("重命名", icon = MintIcons.Tag) {
                                        select(OrganizationAction("重命名标签", tag.name) { repository.renameTag(tag.id, it) })
                                    }
                                    ComposablesMenuItem("移动", icon = MintIcons.Folder) {
                                        select(
                                            OrganizationAction("移动到", choices = listOf("" to "顶级") + available) {
                                                repository.moveTag(tag.id, it)
                                            },
                                        )
                                    }
                                    if (available.isNotEmpty()) {
                                        ComposablesMenuItem("合并", icon = MintIcons.Link) {
                                            select(
                                                OrganizationAction("合并到", choices = available) {
                                                    repository.mergeTags(tag.id, it)
                                                    if (filter.tag == tag.id) onFilter(filter.copy(tag = it))
                                                },
                                            )
                                        }
                                    }
                                    ComposablesMenuItem("删除标签", icon = MintIcons.Trash, destructive = true) {
                                        select(
                                            OrganizationAction("删除标签「${tag.name}」？", destructive = true) {
                                                repository.deleteTag(tag.id)
                                                if (filter.tag == tag.id) onFilter(filter.copy(tag = null))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    action?.let { current ->
        key(current) {
            OrganizationDialog(action = current, dark = dark) { action = null }
        }
    }
}

@Composable
private fun OrganizationSectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    dark: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        Icon(
            imageVector = if (expanded) MintIcons.ChevronDown else MintIcons.ChevronRight,
            contentDescription = if (expanded) "收起$title" else "展开$title",
            tint = scheme.onSurfaceVariant.copy(alpha = 0.56f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun OrganizationGroupRow(
    label: String,
    count: Int?,
    selected: Boolean,
    icon: ImageVector,
    dark: Boolean,
    menuTag: String,
    menuDescription: String,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val highlight = if (selected) {
        Modifier
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(mintHighlight(dark))
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(highlight)
            .defaultMinSize(minHeight = 48.dp)
            .padding(start = if (selected) 10.dp else 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = MintIcons.Check,
                    contentDescription = "已选中",
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        NoteMenuTrigger(
            label = menuDescription,
            tag = menuTag,
            icon = MintIcons.More,
            containerColor = Color.Transparent,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            onClick = onMenuClick,
        )
    }
}

@Composable
private fun OrganizationDialog(
    action: OrganizationAction,
    dark: Boolean,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf(action.initial.orEmpty()) }
    var choice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                action.confirm(choice ?: input)
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: IllegalArgumentException) {
                error = problem.message
            } catch (_: SQLiteConstraintException) {
                error = "名称重复，或目标已变更"
            } catch (_: Exception) {
                error = "保存失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    if (action.choices.isNotEmpty()) {
        MintBottomSheet(
            onDismissRequest = { if (!busy) close() },
            dark = dark,
            title = action.title,
            leadingLabel = "取消",
            onLeadingClick = { if (!busy) close() },
            trailingLabel = "确认",
            onTrailingClick = ::submit,
            trailingEnabled = !busy && choice != null,
            dismissOnClickOutside = !busy,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    action.choices.forEachIndexed { index, (id, name) ->
                        if (index > 0) MintGroupedDivider(dark = dark)
                        MintSelectionRow(
                            label = name,
                            selected = choice == id,
                            dark = dark,
                            multiSelect = false,
                            onClick = { if (!busy) choice = id },
                        )
                    }
                }
                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
            }
        }
    } else {
        MintAlertDialog(
            onDismissRequest = { if (!busy) close() },
            dark = dark,
            title = action.title,
            subtitle = if (action.destructive && action.initial == null) {
                "删除后不会删除笔记正文，仅移除归属关系。"
            } else {
                null
            },
            dismissLabel = "取消",
            onDismissAction = { if (!busy) close() },
            confirmLabel = "确认",
            onConfirmAction = ::submit,
            confirmEnabled = !busy && (action.initial == null || input.isNotBlank()),
            destructive = action.destructive,
            dismissOnClickOutside = !busy,
        ) {
            if (action.initial != null) {
                MintTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    placeholder = "输入名称",
                    dark = dark,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy && input.isNotBlank()) submit() }),
                )
            }
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
        }
    }
}

@Composable
internal fun NoteOrganizationDialog(
    note: String,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    tagIds: Set<String>,
    notebookId: String?,
    repository: OrganizationRepository,
    close: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var creatingTag by remember { mutableStateOf(tags.isEmpty()) }
    var newTagName by remember { mutableStateOf("") }
    val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    val sortedTags = remember(paths, tags) { tags.sortedBy { paths.getValue(it.id) } }

    fun change(action: suspend () -> Unit) {
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                action()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: IllegalArgumentException) {
                error = problem.message
            } catch (_: SQLiteConstraintException) {
                error = "已存在同名标签"
            } catch (_: Exception) {
                error = "保存失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    fun createAndAttachTag() {
        val trimmed = newTagName.trim()
        if (trimmed.isEmpty()) {
            error = "请输入标签名称"
            return
        }
        change {
            val id = repository.createTag(trimmed)
            repository.tagNote(note, id, true)
            newTagName = ""
            creatingTag = false
        }
    }

    MintBottomSheet(
        onDismissRequest = { if (!busy) close() },
        dark = dark,
        title = "整理笔记",
        subtitle = "选择所属笔记本与标签，修改立即保存",
        leadingLabel = null,
        trailingLabel = "完成",
        onTrailingClick = close,
        trailingEnabled = !busy,
        dismissOnClickOutside = !busy,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "标签",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
                TextButton(
                    onClick = { creatingTag = !creatingTag },
                    enabled = !busy,
                ) {
                    Text(if (creatingTag) "收起新建" else "+ 新建标签")
                }
            }
            AnimatedVisibility(visible = creatingTag) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MintTextField(
                        value = newTagName,
                        onValueChange = {
                            newTagName = it
                            error = null
                        },
                        placeholder = "输入新标签名称",
                        dark = dark,
                        leadingIcon = MintIcons.Tag,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy) createAndAttachTag() }),
                    )
                    MintPillButton(
                        label = "添加",
                        primary = true,
                        dark = dark,
                        enabled = !busy,
                        onClick = ::createAndAttachTag,
                    )
                }
            }
            if (sortedTags.isEmpty()) {
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    Text(
                        text = "暂无标签，可在上方直接输入创建并自动打标。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    sortedTags.forEachIndexed { index, tag ->
                        if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        val attached = tag.id in tagIds
                        MintSelectionRow(
                            label = paths.getValue(tag.id),
                            selected = attached,
                            dark = dark,
                            leadingIcon = MintIcons.Tag,
                            multiSelect = true,
                            onClick = {
                                if (!busy) change { repository.tagNote(note, tag.id, !attached) }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "笔记本",
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                val allNotebooks = listOf(null to "未归入笔记本") + notebooks.map { it.id to it.name }
                allNotebooks.forEachIndexed { index, (id, name) ->
                    if (index > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
                    MintSelectionRow(
                        label = name,
                        selected = id == notebookId,
                        dark = dark,
                        leadingIcon = MintIcons.Folder,
                        multiSelect = false,
                        onClick = {
                            if (!busy) change { repository.fileNote(note, id) }
                        },
                    )
                }
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }
        }
    }
}
