package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenuItem
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesSearchField
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIconButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class ActiveConditionChip(
    val key: String,
    val label: String,
    val remove: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySearchControls(
    repository: SearchRepository,
    keyword: TextFieldState,
    scope: NoteScope,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    onFilter: (NoteScope) -> Unit,
    resultCount: Int? = null,
    hasExtraFilter: Boolean = false,
    onResetExtraFilter: (() -> Unit)? = null,
    dark: Boolean = isSystemInDarkTheme(),
) {
    val scheme = MaterialTheme.colorScheme
    val coroutine = rememberCoroutineScope()
    val saved by repository.saved.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var renamingItem by remember { mutableStateOf<SavedSearch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val active = scope != NoteScope()
    val canSave = active || keyword.text.isNotBlank()
    val hasAnyFilter = active || keyword.text.isNotBlank() || hasExtraFilter

    fun restore(item: SavedSearch) {
        try {
            val restored = NoteScope.decode(item.scopeJson)
            keyword.edit { replace(0, length, item.keyword) }
            onFilter(restored)
            dialog = null
            error = null
        } catch (_: Exception) {
            error = "无法读取这条搜索条件"
        }
    }

    fun clearAllFilters() {
        keyword.edit { replace(0, length, "") }
        onFilter(NoteScope())
        onResetExtraFilter?.invoke()
        error = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                ComposablesSearchField(
                    state = keyword,
                    label = "搜索笔记",
                    tag = "library-search",
                    containerColor = mintSecondaryFill(dark),
                )
            }
            MintIconButton(
                onClick = { dialog = "筛选" },
                contentDescription = "筛选",
                dark = dark,
                icon = MintIcons.Filter,
                tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                containerColor = if (active) {
                    scheme.primaryContainer
                } else {
                    mintSecondaryFill(dark)
                },
            )
            if (canSave || saved.isNotEmpty()) {
                Box {
                    MintIconButton(
                        onClick = { menu = true },
                        contentDescription = "更多搜索操作",
                        dark = dark,
                        icon = MintIcons.More,
                        tint = scheme.onSurfaceVariant,
                        containerColor = mintSecondaryFill(dark),
                    )
                    ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                        if (canSave) {
                            ComposablesMenuItem("保存条件", icon = MintIcons.SmartFolder) {
                                menu = false
                                dialog = "保存条件"
                            }
                        }
                        if (saved.isNotEmpty()) {
                            ComposablesMenuItem("管理已保存搜索", icon = MintIcons.Library) {
                                menu = false
                                dialog = "已保存搜索"
                            }
                        }
                    }
                }
            }
        }

        if (active) {
            val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
            val activeChips = buildList {
                if (scope.untagged) {
                    add(ActiveConditionChip("untagged", "无标签") { onFilter(scope.copy(untagged = false)) })
                }
                scope.includeTags.forEach { tagId ->
                    paths[tagId]?.let { label ->
                        add(ActiveConditionChip("inc:$tagId", "#$label") {
                            onFilter(scope.copy(includeTags = scope.includeTags - tagId))
                        })
                    }
                }
                scope.excludeTags.forEach { tagId ->
                    paths[tagId]?.let { label ->
                        add(ActiveConditionChip("exc:$tagId", "排除 $label") {
                            onFilter(scope.copy(excludeTags = scope.excludeTags - tagId))
                        })
                    }
                }
                notebooks.filter { it.id in scope.notebooks }.forEach { book ->
                    add(ActiveConditionChip("nb:${book.id}", book.name) {
                        onFilter(scope.copy(notebooks = scope.notebooks - book.id))
                    })
                }
                sources.filter { it.first in scope.sources }.forEach { (id, label) ->
                    add(ActiveConditionChip("src:$id", label) {
                        onFilter(scope.copy(sources = scope.sources - id))
                    })
                }
                mediaChoices.filter { it.first in scope.media }.forEach { (id, label) ->
                    add(ActiveConditionChip("media:$id", label) {
                        onFilter(scope.copy(media = scope.media - id))
                    })
                }
                if (scope.from != null || scope.until != null) {
                    add(ActiveConditionChip("date", if (scope.updatedTime) "修改日期" else "创建日期") {
                        onFilter(scope.copy(from = null, until = null))
                    })
                }
                if (scope.archived) {
                    add(ActiveConditionChip("archived", "含归档") {
                        onFilter(scope.copy(archived = false))
                    })
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                activeChips.forEach { chip ->
                    key(chip.key) {
                        MintChip(
                            label = chip.label,
                            onClick = chip.remove,
                            dark = dark,
                            style = MintChipStyle.ActiveFilter,
                            removable = true,
                        )
                    }
                }
                MintChip(
                    label = "清空",
                    onClick = ::clearAllFilters,
                    dark = dark,
                    style = MintChipStyle.Standard,
                    modifier = Modifier.semantics { contentDescription = "重置条件" },
                )
            }
        }

        if (saved.isNotEmpty()) {
            val shortcuts = rememberLazyListState()
            LaunchedEffect(saved.first().id) { shortcuts.scrollToItem(0) }
            LazyRow(
                state = shortcuts,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(saved, key = { it.id }) { item ->
                    var itemMenu by remember(item.id) { mutableStateOf(false) }
                    val itemScope = remember(item.scopeJson) {
                        runCatching { NoteScope.decode(item.scopeJson) }.getOrNull()
                    }
                    val isSelected = itemScope != null &&
                        (item.keyword.isNotBlank() || itemScope != NoteScope()) &&
                        item.keyword.trim() == keyword.text.toString().trim() &&
                        itemScope == scope
                    Box {
                        SmartFolderPill(
                            name = item.name,
                            active = isSelected,
                            dark = dark,
                            onClick = { restore(item) },
                            onMoreClick = { itemMenu = true },
                        )
                        ComposablesMenu(expanded = itemMenu, onDismiss = { itemMenu = false }) {
                            ComposablesMenuItem("重命名", icon = MintIcons.SmartFolder) {
                                itemMenu = false
                                renamingItem = item
                            }
                            ComposablesMenuItem("删除搜索", icon = MintIcons.Close, destructive = true) {
                                itemMenu = false
                                coroutine.launch {
                                    try {
                                        repository.delete(item.id)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        error = "无法删除搜索条件，请重试"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (resultCount != null || hasAnyFilter) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (resultCount != null) {
                    Text(
                        text = "共 $resultCount 条笔记",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (hasAnyFilter && !active) {
                    Text(
                        text = "清空",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .semantics { contentDescription = "重置条件" }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = ::clearAllFilters,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (dialog == null && renamingItem == null) {
            error?.let {
                Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    when (dialog) {
        "筛选" -> ScopeDialog(
            initial = scope,
            tags = tags,
            notebooks = notebooks,
            dark = dark,
            close = { dialog = null },
        ) {
            onFilter(it)
            dialog = null
        }

        "保存条件" -> {
            var name by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            fun saveCurrent() {
                if (name.isBlank() || busy) return
                busy = true
                coroutine.launch {
                    try {
                        repository.save(name, keyword.text.toString(), scope)
                        dialog = null
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (problem: IllegalArgumentException) {
                        error = problem.message
                    } catch (_: Exception) {
                        error = "无法保存搜索条件，请重试"
                    } finally {
                        busy = false
                    }
                }
            }
            MintAlertDialog(
                onDismissRequest = { if (!busy) dialog = null },
                dark = dark,
                dismissOnClickOutside = !busy,
                title = "保存搜索条件",
                subtitle = "保存后将作为智能文件夹显示在搜索栏下方",
                dismissLabel = "取消",
                onDismissAction = { dialog = null },
                confirmLabel = "保存搜索",
                onConfirmAction = ::saveCurrent,
                confirmEnabled = name.isNotBlank() && !busy,
            ) {
                MintTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    placeholder = "输入智能文件夹名称",
                    dark = dark,
                    leadingIcon = MintIcons.SmartFolder,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveCurrent() }),
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        "已保存搜索" -> {
            var deleting by remember { mutableStateOf<SavedSearch?>(null) }
            MintBottomSheet(
                onDismissRequest = { dialog = null },
                dark = dark,
                title = "已保存搜索",
                leadingLabel = null,
                trailingLabel = "完成",
                onTrailingClick = { dialog = null },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (saved.isEmpty()) {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            Text(
                                text = "还没有保存的搜索",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            saved.forEachIndexed { index, item ->
                                if (index > 0) MintGroupedDivider(dark = dark, startIndent = 44.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = MintIcons.SmartFolder,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                                onClick = { restore(item) },
                                            )
                                            .padding(vertical = 8.dp),
                                    )
                                    TextButton(onClick = { deleting = item }) {
                                        Text(
                                            text = "删除",
                                            color = scheme.error,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    error?.let {
                        Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            deleting?.let { item ->
                MintAlertDialog(
                    onDismissRequest = { deleting = null },
                    dark = dark,
                    title = "删除「${item.name}」？",
                    subtitle = "仅移除这条智能文件夹快捷方式，不会删除笔记。",
                    dismissLabel = "取消",
                    onDismissAction = { deleting = null },
                    confirmLabel = "删除搜索",
                    destructive = true,
                    onConfirmAction = {
                        deleting = null
                        coroutine.launch {
                            try {
                                repository.delete(item.id)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "无法删除搜索条件，请重试"
                            }
                        }
                    },
                )
            }
        }
    }

    renamingItem?.let { item ->
        var newName by remember(item.id) { mutableStateOf(item.name) }
        var busy by remember(item.id) { mutableStateOf(false) }
        fun submitRename() {
            if (newName.isBlank() || busy) return
            busy = true
            coroutine.launch {
                try {
                    repository.rename(item.id, newName)
                    renamingItem = null
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (problem: IllegalArgumentException) {
                    error = problem.message
                } catch (_: Exception) {
                    error = "无法重命名搜索条件，请重试"
                } finally {
                    busy = false
                }
            }
        }
        MintAlertDialog(
            onDismissRequest = { if (!busy) renamingItem = null },
            dark = dark,
            dismissOnClickOutside = !busy,
            title = "重命名智能文件夹",
            dismissLabel = "取消",
            onDismissAction = { if (!busy) renamingItem = null },
            confirmLabel = "保存",
            confirmEnabled = newName.isNotBlank() && !busy,
            onConfirmAction = ::submitRename,
        ) {
            MintTextField(
                value = newName,
                onValueChange = {
                    newName = it
                    error = null
                },
                placeholder = "输入新名称",
                dark = dark,
                leadingIcon = MintIcons.SmartFolder,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitRename() }),
            )
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SmartFolderPill(
    name: String,
    active: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val fill = if (active) {
        if (dark) Color(0xFF23523E) else Color(0xFFD7F2E4)
    } else {
        mintCardSurface(dark)
    }
    val contentColor = if (active) {
        if (dark) Color(0xFFAAF0CB) else Color(0xFF0E5A3A)
    } else {
        scheme.onSurface
    }
    val iconTint = if (active) contentColor else scheme.primary
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .widthIn(max = 260.dp)
            .clip(shape)
            .background(fill, shape)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = MintIcons.SmartFolder,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "管理已保存搜索 $name",
                    onClick = onMoreClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MintIcons.More,
                contentDescription = null,
                tint = if (active) contentColor.copy(alpha = 0.85f) else scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private val mediaChoices = listOf("image" to "图片", "audio" to "音频", "video" to "视频")

private val sources = listOf(
    "manual" to "手动记录",
    "voice" to "语音",
    "share" to "系统分享",
    "import" to "导入",
    "agent" to "Agent",
    "insight" to "洞察",
    "web" to "网页",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeDialog(
    initial: NoteScope,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    dark: Boolean,
    close: () -> Unit,
    apply: (NoteScope) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val zone = remember { ZoneId.systemDefault() }
    var scope by remember { mutableStateOf(initial) }
    var start by remember { mutableStateOf(initial.from?.atZone(zone)?.toLocalDate()?.toString().orEmpty()) }
    var end by remember { mutableStateOf(initial.until?.atZone(zone)?.toLocalDate()?.minusDays(1)?.toString().orEmpty()) }
    var customDateExpanded by remember { mutableStateOf(start.isNotEmpty() || end.isNotEmpty()) }
    var error by remember { mutableStateOf(false) }

    val todayStr = remember { LocalDate.now(zone).toString() }
    val weekStartStr = remember { LocalDate.now(zone).minusDays(6).toString() }
    val lastYearStr = remember { LocalDate.now(zone).minusYears(1).toString() }

    val preset = when {
        start == todayStr && end == todayStr -> "today"
        start == weekStartStr && end == todayStr -> "week"
        start == lastYearStr && end == lastYearStr -> "last_year"
        start.isNotEmpty() || end.isNotEmpty() -> "custom"
        else -> "none"
    }

    MintBottomSheet(
        onDismissRequest = close,
        dark = dark,
        title = "筛选笔记",
        leadingLabel = "取消",
        onLeadingClick = close,
        trailingLabel = "应用筛选",
        onTrailingClick = {
            try {
                val from = start.trim().takeIf(String::isNotEmpty)
                    ?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() }
                val until = end.trim().takeIf(String::isNotEmpty)
                    ?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
                if (from != null && until != null && !until.isAfter(from)) {
                    error = true
                } else {
                    apply(scope.copy(from = from, until = until))
                }
            } catch (_: IllegalArgumentException) {
                error = true
            } catch (_: java.time.DateTimeException) {
                error = true
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "时间范围",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            MintSegmentedBar(
                options = listOf("按创建日期", "按修改日期"),
                selectedIndex = if (scope.updatedTime) 1 else 0,
                onSelect = { scope = scope.copy(updatedTime = it == 1) },
                dark = dark,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MintChip(
                    label = "今天",
                    dark = dark,
                    leadingIcon = MintIcons.Calendar,
                    style = if (preset == "today") MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = {
                        if (preset == "today") {
                            start = ""
                            end = ""
                        } else {
                            start = todayStr
                            end = todayStr
                            customDateExpanded = false
                        }
                        error = false
                    },
                )
                MintChip(
                    label = "近 7 天",
                    dark = dark,
                    leadingIcon = MintIcons.Calendar,
                    style = if (preset == "week") MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = {
                        if (preset == "week") {
                            start = ""
                            end = ""
                        } else {
                            start = weekStartStr
                            end = todayStr
                            customDateExpanded = false
                        }
                        error = false
                    },
                )
                MintChip(
                    label = "去年今日",
                    dark = dark,
                    leadingIcon = MintIcons.Calendar,
                    style = if (preset == "last_year") MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = {
                        if (preset == "last_year") {
                            start = ""
                            end = ""
                        } else {
                            start = lastYearStr
                            end = lastYearStr
                            scope = scope.copy(updatedTime = false)
                            customDateExpanded = false
                        }
                        error = false
                    },
                )
                MintChip(
                    label = "自定义",
                    dark = dark,
                    style = if (customDateExpanded || preset == "custom") MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = { customDateExpanded = !customDateExpanded },
                )
            }
            AnimatedVisibility(visible = customDateExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MintTextField(
                        value = start,
                        onValueChange = {
                            start = it
                            error = false
                        },
                        placeholder = "开始日期 YYYY-MM-DD",
                        dark = dark,
                        leadingIcon = MintIcons.Calendar,
                    )
                    MintTextField(
                        value = end,
                        onValueChange = {
                            end = it
                            error = false
                        },
                        placeholder = "结束日期 YYYY-MM-DD",
                        dark = dark,
                        leadingIcon = MintIcons.Calendar,
                    )
                }
            }
            if (error) {
                Text(
                    text = "请输入有效日期，结束日期须在开始日期当天或之后。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }

            Text(
                text = "状态与标签",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MintChip(
                    label = "包含归档",
                    dark = dark,
                    leadingIcon = MintIcons.Archive,
                    style = if (scope.archived) MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = { scope = scope.copy(archived = !scope.archived) },
                )
                MintChip(
                    label = "仅无标签",
                    dark = dark,
                    leadingIcon = MintIcons.Tag,
                    style = if (scope.untagged) MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = {
                        scope = scope.copy(untagged = !scope.untagged, includeTags = emptySet())
                    },
                )
            }
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MintChip(
                        label = "包含下级",
                        dark = dark,
                        style = if (scope.descendants) MintChipStyle.Selected else MintChipStyle.Standard,
                        onClick = { scope = scope.copy(descendants = !scope.descendants) },
                    )
                    MintChip(
                        label = if (scope.matchAll) "匹配全部标签" else "匹配任一标签",
                        dark = dark,
                        style = if (scope.matchAll) MintChipStyle.Selected else MintChipStyle.Standard,
                        onClick = { scope = scope.copy(matchAll = !scope.matchAll) },
                    )
                }
                val labels = remember(tags) { tags.map { it.id to tagPath(tags, it) } }
                if (!scope.untagged) {
                    ChoiceChips("包含标签", labels, scope.includeTags, dark) {
                        scope = scope.copy(includeTags = it)
                    }
                }
                ChoiceChips("排除标签", labels, scope.excludeTags, dark) {
                    scope = scope.copy(excludeTags = it)
                }
            }
            if (notebooks.isNotEmpty()) {
                ChoiceChips("笔记本", notebooks.map { it.id to it.name }, scope.notebooks, dark) {
                    scope = scope.copy(notebooks = it)
                }
            }
            ChoiceChips("来源", sources, scope.sources, dark) {
                scope = scope.copy(sources = it)
            }
            ChoiceChips("媒体", mediaChoices, scope.media, dark) {
                scope = scope.copy(media = it)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(
    title: String,
    items: List<Pair<String, String>>,
    selected: Set<String>,
    dark: Boolean,
    change: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { (id, name) ->
                val isSelected = id in selected
                MintChip(
                    label = name,
                    dark = dark,
                    style = if (isSelected) MintChipStyle.Selected else MintChipStyle.Standard,
                    onClick = { change(if (isSelected) selected - id else selected + id) },
                )
            }
        }
    }
}
