package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenuItem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesSearchField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySearchControls(
    repository: SearchRepository,
    keyword: TextFieldState,
    scope: NoteScope,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    onFilter: (NoteScope) -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    val saved by repository.saved.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val active = scope != NoteScope()
    val canSave = active || keyword.text.isNotBlank()
    fun restore(item: SavedSearch) {
        try {
            val restored = NoteScope.decode(item.scopeJson)
            keyword.edit { replace(0, length, item.keyword) }
            onFilter(restored)
            dialog = null
            error = null
        } catch (_: Exception) { error = "无法读取这条搜索条件" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ComposablesSearchField(keyword, "搜索笔记", tag = "library-search") }
            SearchIcon("筛选", active = active) { dialog = "筛选" }
            if (canSave || saved.isNotEmpty()) Box {
                SearchIcon("更多搜索操作", more = true) { menu = true }
                ComposablesMenu(menu, { menu = false }) {
                    if (canSave) ComposablesMenuItem("保存条件") { menu = false; dialog = "保存条件" }
                    if (saved.isNotEmpty()) ComposablesMenuItem("管理已保存搜索") { menu = false; dialog = "已保存搜索" }
                }
            }
        }
        if (active) {
            val paths = tags.associate { it.id to tagPath(tags, it) }
            val labels = buildList {
                if (scope.untagged) add("无标签")
                addAll(scope.includeTags.mapNotNull(paths::get))
                addAll(scope.excludeTags.mapNotNull { paths[it]?.let { name -> "排除 $name" } })
                addAll(notebooks.filter { it.id in scope.notebooks }.map { it.name })
                addAll(sources.filter { it.first in scope.sources }.map { it.second })
                addAll(mediaChoices.filter { it.first in scope.media }.map { it.second })
                if (scope.from != null || scope.until != null) add(if (scope.updatedTime) "修改日期" else "创建日期")
                if (scope.archived) add("含归档")
            }
            AssistChip(onClick = { onFilter(NoteScope()) }, label = {
                Text(labels.take(2).joinToString(" · ").ifEmpty { "筛选条件" } +
                    if (labels.size > 2) " ＋${labels.size - 2}" else "", maxLines = 1)
            }, trailingIcon = { Text("×", Modifier.semantics { contentDescription = "重置条件" }) },
                shape = CircleShape)
        }
        if (saved.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(saved, key = { it.id }) { item ->
                SuggestionChip(onClick = { restore(item) }, label = { Text(item.name) }, shape = CircleShape)
            }
        }
        if (dialog == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    when (dialog) {
        "筛选" -> ScopeDialog(scope, tags, notebooks, { dialog = null }) { onFilter(it); dialog = null }
        "保存条件" -> {
            var name by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            SearchDialog(onDismissRequest = { if (!busy) dialog = null }, title = { Text("保存搜索条件") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") })
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank() && !busy, onClick = {
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
                            } finally { busy = false }
                        }
                    }) { Text("保存搜索") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }, enabled = !busy) { Text("取消") } })
        }
        "已保存搜索" -> {
            var deleting by remember { mutableStateOf<SavedSearch?>(null) }
            SearchDialog(onDismissRequest = { dialog = null }, title = { Text("已保存搜索") },
                text = {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        if (saved.isEmpty()) Text("还没有保存的搜索")
                        saved.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { restore(item) }, modifier = Modifier.weight(1f)) { Text(item.name) }
                                TextButton(onClick = { deleting = item }) { Text("删除") }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
            deleting?.let { item ->
                SearchDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${item.name}」？") },
                    confirmButton = { TextButton(onClick = {
                        deleting = null
                        coroutine.launch {
                            try { repository.delete(item.id) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "无法删除搜索条件，请重试" }
                        }
                    }) { Text("删除搜索") } },
                    dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
            }
        }
    }
}

@Composable
private fun SearchIcon(label: String, active: Boolean = false, more: Boolean = false, action: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(48.dp).clip(CircleShape)
        .background(if (active) scheme.primaryContainer else scheme.surfaceContainer)
        .semantics { contentDescription = label }
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            if (more) {
                for (i in -1..1) drawCircle(scheme.onSurface, 1.4.dp.toPx(), center + Offset(i * 5.dp.toPx(), 0f))
            } else {
                for (i in 0..2) {
                    val y = size.height * (.22f + i * .28f)
                    val knob = size.width * (if (i == 1) .64f else .36f)
                    drawLine(scheme.onSurface, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), StrokeCap.Round)
                    drawCircle(scheme.onSurface, 2.25.dp.toPx(), Offset(knob, y))
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val backdrop = rememberGlassBackdrop()
    Dialog(onDismissRequest, properties = DialogProperties()) {
        GlassSurface(backdrop, GlassTier.Reduced, dark, Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) {
                    title()
                }
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}

private val mediaChoices = listOf("image" to "图片", "audio" to "音频", "video" to "视频")

private val sources = listOf(
    "manual" to "手动记录", "voice" to "语音", "share" to "系统分享",
    "import" to "导入", "agent" to "Agent", "insight" to "洞察", "web" to "网页",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeDialog(
    initial: NoteScope,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    close: () -> Unit,
    apply: (NoteScope) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var scope by remember { mutableStateOf(initial) }
    var start by remember { mutableStateOf(initial.from?.atZone(zone)?.toLocalDate()?.toString().orEmpty()) }
    var end by remember { mutableStateOf(initial.until?.atZone(zone)?.toLocalDate()?.minusDays(1)?.toString().orEmpty()) }
    var error by remember { mutableStateOf(false) }
    SearchDialog(onDismissRequest = close, title = { Text("筛选笔记") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.clickable { scope = scope.copy(updatedTime = !scope.updatedTime) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(scope.updatedTime, null, Modifier.padding(end = 10.dp))
                    Text(if (scope.updatedTime) "按修改日期" else "按创建日期")
                }
                OutlinedTextField(start, { start = it; error = false }, singleLine = true, label = { Text("开始日期 YYYY-MM-DD") })
                OutlinedTextField(end, { end = it; error = false }, singleLine = true, label = { Text("结束日期 YYYY-MM-DD") })
                TextButton(onClick = {
                    val date = LocalDate.now(zone).minusYears(1)
                    start = date.toString()
                    end = start
                    scope = scope.copy(updatedTime = false)
                    error = false
                }) { Text("去年今日") }
                if (error) Text("请输入有效日期，结束日期须在开始日期当天或之后。", color = MaterialTheme.colorScheme.error)

                FilterChip(scope.archived, { scope = scope.copy(archived = !scope.archived) }, { Text("包含归档") })
                FilterChip(scope.untagged, {
                    scope = scope.copy(untagged = !scope.untagged, includeTags = emptySet())
                }, { Text("仅无标签") })
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(scope.descendants, { scope = scope.copy(descendants = !scope.descendants) }, { Text("包含下级") })
                        FilterChip(scope.matchAll, { scope = scope.copy(matchAll = !scope.matchAll) }, {
                            Text(if (scope.matchAll) "匹配全部标签" else "匹配任一标签")
                        })
                    }
                    val labels = tags.map { it.id to tagPath(tags, it) }
                    if (!scope.untagged) ChoiceChips("包含标签", labels, scope.includeTags) { scope = scope.copy(includeTags = it) }
                    ChoiceChips("排除标签", labels, scope.excludeTags) { scope = scope.copy(excludeTags = it) }
                }
                if (notebooks.isNotEmpty()) {
                    ChoiceChips("笔记本", notebooks.map { it.id to it.name }, scope.notebooks) { scope = scope.copy(notebooks = it) }
                }
                ChoiceChips("来源", sources, scope.sources) { scope = scope.copy(sources = it) }
                ChoiceChips("媒体", mediaChoices, scope.media) {
                    scope = scope.copy(media = it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val from = start.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() }
                    val until = end.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
                    apply(scope.copy(from = from, until = until))
                } catch (_: IllegalArgumentException) { error = true }
                catch (_: java.time.DateTimeException) { error = true }
            }) { Text("应用筛选") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(title: String, items: List<Pair<String, String>>, selected: Set<String>, change: (Set<String>) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (id, name) ->
            FilterChip(id in selected, { change(if (id in selected) selected - id else selected + id) }, { Text(name) })
        }
    }
}
