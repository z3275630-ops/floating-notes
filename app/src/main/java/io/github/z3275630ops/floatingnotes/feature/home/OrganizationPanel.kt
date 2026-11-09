package io.github.z3275630ops.floatingnotes.feature.home

import android.database.sqlite.SQLiteConstraintException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class LibraryFilter(val tag: String? = null, val notebook: String? = null, val descendants: Boolean = true)

private data class OrganizationAction(
    val title: String,
    val initial: String? = null,
    val choices: List<Pair<String, String>> = emptyList(),
    val confirm: suspend (String) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrganizationPanel(
    repository: OrganizationRepository,
    tags: List<TagEntity>,
    notebooks: List<NotebookEntity>,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
) {
    var action by remember { mutableStateOf<OrganizationAction?>(null) }
    val paths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                action = OrganizationAction("新建标签", "") { repository.createTag(it) }
            }) { Text("新建标签") }
            TextButton(onClick = {
                action = OrganizationAction("新建笔记本", "") { repository.createNotebook(it) }
            }) { Text("新建笔记本") }
            TextButton(onClick = { onFilter(LibraryFilter()) }) { Text("清除筛选") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(filter.descendants, { onFilter(filter.copy(descendants = it)) })
            Text("包含下级标签")
        }
        for (tag in tags.sortedBy { paths.getValue(it.id) }) {
            val name = paths.getValue(tag.id)
            var menu by remember(tag.id) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(filter.tag == tag.id, {
                    onFilter(filter.copy(tag = tag.id.takeUnless { it == filter.tag }))
                }, { Text(name) }, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("管理") }
                    DropdownMenu(menu, { menu = false }) {
                        fun select(option: OrganizationAction) { menu = false; action = option }
                        val available = tags.filterNot { it.id in tagSubtree(tags, tag.id) }
                            .map { it.id to paths.getValue(it.id) }
                        DropdownMenuItem(text = { Text("添加下级") }, onClick = {
                            select(OrganizationAction("新建下级标签", "") { repository.createTag(it, tag.id) })
                        })
                        DropdownMenuItem(text = { Text("重命名") }, onClick = {
                            select(OrganizationAction("重命名标签", tag.name) { repository.renameTag(tag.id, it) })
                        })
                        DropdownMenuItem(text = { Text("移动") }, onClick = {
                            select(OrganizationAction("移动到", choices = listOf("" to "顶级") + available) {
                                repository.moveTag(tag.id, it)
                            })
                        })
                        DropdownMenuItem(text = { Text("合并") }, enabled = available.isNotEmpty(), onClick = {
                            select(OrganizationAction("合并到", choices = available) {
                                repository.mergeTags(tag.id, it)
                                if (filter.tag == tag.id) onFilter(filter.copy(tag = it))
                            })
                        })
                        DropdownMenuItem(text = { Text("删除标签") }, onClick = {
                            select(OrganizationAction("删除标签「${tag.name}」？") {
                                repository.deleteTag(tag.id)
                                if (filter.tag == tag.id) onFilter(filter.copy(tag = null))
                            })
                        })
                    }
                }
            }
        }
        for (notebook in notebooks) {
            var menu by remember(notebook.id) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(filter.notebook == notebook.id, {
                    onFilter(filter.copy(notebook = notebook.id.takeUnless { it == filter.notebook }))
                }, { Text("笔记本 · ${notebook.name}") }, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("管理") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("重命名") }, onClick = {
                            menu = false
                            action = OrganizationAction("重命名笔记本", notebook.name) { repository.renameNotebook(notebook.id, it) }
                        })
                        DropdownMenuItem(text = { Text("删除笔记本") }, onClick = {
                            menu = false
                            action = OrganizationAction("删除笔记本「${notebook.name}」？") {
                                repository.deleteNotebook(notebook.id)
                                if (filter.notebook == notebook.id) onFilter(filter.copy(notebook = null))
                            }
                        })
                    }
                }
            }
        }
    }
    action?.let { current -> key(current) { OrganizationDialog(current) { action = null } } }
}

@Composable
private fun OrganizationDialog(action: OrganizationAction, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(action.initial.orEmpty()) }
    var choice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(action.title) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (action.initial != null) OutlinedTextField(input, { input = it }, label = { Text("名称") }, singleLine = true)
                action.choices.forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == id, { choice = id }, enabled = !busy)
                        Text(name)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (action.choices.isEmpty() || choice != null), onClick = {
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
            }) { Text("确认") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } },
    )
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
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun change(action: suspend () -> Unit) {
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            try { action(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "保存失败，请重试" }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("整理笔记") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("标签")
                if (tags.isEmpty()) Text("到笔记库新建标签")
                tags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(tag.id in tagIds, { attach -> change { repository.tagNote(note, tag.id, attach) } }, enabled = !busy)
                        Text(tagPath(tags, tag))
                    }
                }
                Text("笔记本")
                (listOf(null to "未归入笔记本") + notebooks.map { it.id to it.name }).forEach { (id, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(id == notebookId, { change { repository.fileNote(note, id) } }, enabled = !busy)
                        Text(name)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = !busy) { Text("完成") } },
    )
}
