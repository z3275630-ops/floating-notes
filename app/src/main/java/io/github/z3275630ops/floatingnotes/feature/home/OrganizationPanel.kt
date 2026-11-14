package io.github.z3275630ops.floatingnotes.feature.home

import android.database.sqlite.SQLiteConstraintException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenuItem
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGlassDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSelectionRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.NoteMenuTrigger
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    containerColor = if (dark) Color(0xFF1A2B22) else Color(0xFFF3F8F4),
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
            Text(
                text = "还没有笔记本或标签，点右上角可快速新建。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        if (notebooks.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(notebooks, key = { "nb:${it.id}" }) { notebook ->
                    var menu by remember(notebook.id) { mutableStateOf(false) }
                    val selected = filter.notebook == notebook.id
                    Box {
                        OrganizationStripPill(
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
                            ComposablesMenuItem("删除笔记本", icon = MintIcons.Close, destructive = true) {
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

        if (sortedTags.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(sortedTags, key = { "tag:${it.id}" }) { tag ->
                    val name = paths.getValue(tag.id)
                    var menu by remember(tag.id) { mutableStateOf(false) }
                    val selected = filter.tag == tag.id
                    Box {
                        OrganizationStripPill(
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
                            ComposablesMenuItem("删除标签", icon = MintIcons.Close, destructive = true) {
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
    action?.let { current ->
        key(current) {
            OrganizationDialog(action = current, dark = dark) { action = null }
        }
    }
}

@Composable
private fun OrganizationStripPill(
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
    val shape = RoundedCornerShape(50)
    val fill = when {
        selected && dark -> Color(0xFF23523E)
        selected -> Color(0xFFD7F2E4)
        dark -> Color(0xFF1A2B22)
        else -> Color(0xFFFAFDF9)
    }
    val textColor = when {
        selected && dark -> Color(0xFFAAF0CB)
        selected -> Color(0xFF0E5A3A)
        else -> scheme.onSurface
    }
    val border = if (selected) {
        scheme.primary.copy(alpha = if (dark) 0.72f else 0.50f)
    } else {
        scheme.outline.copy(alpha = if (dark) 0.42f else 0.30f)
    }

    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(fill, shape)
            .border(if (selected) 1.2.dp else 0.9.dp, border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) textColor else scheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(textColor.copy(alpha = 0.11f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 40.dp)
                .clip(CircleShape)
                .testTag(menuTag)
                .semantics { contentDescription = menuDescription }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = menuDescription,
                    onClick = onMenuClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MintIcons.More,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.78f),
                modifier = Modifier.size(15.dp),
            )
        }
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

    MintGlassDialog(
        onDismissRequest = { if (!busy) close() },
        dark = dark,
        dismissOnClickOutside = !busy,
        title = action.title,
        actions = {
            MintPillButton(
                label = "取消",
                dark = dark,
                enabled = !busy,
                onClick = close,
            )
            MintPillButton(
                label = "确认",
                primary = !action.destructive,
                destructive = action.destructive,
                dark = dark,
                enabled = !busy && (action.choices.isEmpty() || choice != null),
                onClick = ::submit,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                )
            }
            action.choices.forEach { (id, name) ->
                MintSelectionRow(
                    label = name,
                    selected = choice == id,
                    dark = dark,
                    multiSelect = false,
                    onClick = { if (!busy) choice = id },
                )
            }
            if (action.destructive && action.initial == null && action.choices.isEmpty()) {
                Text(
                    text = "删除后不会删除笔记正文，仅移除归属关系。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
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

    MintGlassDialog(
        onDismissRequest = { if (!busy) close() },
        dark = dark,
        dismissOnClickOutside = !busy,
        title = "整理笔记",
        subtitle = "选择所属笔记本与标签，修改立即保存",
        actions = {
            MintPillButton(
                label = "完成",
                primary = true,
                icon = MintIcons.Check,
                dark = dark,
                enabled = !busy,
                onClick = close,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
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
                Text(
                    text = "暂无标签，可在上方直接输入创建并自动打标。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                sortedTags.forEach { tag ->
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

            Spacer(Modifier.height(6.dp))
            Text(
                text = "笔记本",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            (listOf(null to "未归入笔记本") + notebooks.map { it.id to it.name }).forEach { (id, name) ->
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
