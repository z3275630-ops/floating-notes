package io.github.z3275630ops.floatingnotes.data.agent

import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

internal enum class MemoryCategory(val id: String, val displayName: String) {
    FOCUS("focus", "长期关注"),
    PREFERENCE("preference", "个人偏好"),
    QUESTION("question", "核心问题"),
    CONTEXT("context", "背景脉络"),
    ;

    companion object {
        fun fromId(raw: String?): MemoryCategory {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return FOCUS
            return entries.firstOrNull {
                it.id.equals(trimmed, ignoreCase = true) ||
                    it.name.equals(trimmed, ignoreCase = true) ||
                    it.displayName == trimmed
            } ?: FOCUS
        }
    }
}

internal data class AgentMemoryItem(
    val id: String,
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val sourceNoteIds: List<String> = emptyList(),
    val enabled: Boolean = true,
    val userEdited: Boolean = false,
    val updatedAt: Long,
)

internal data class AgentMemorySnapshot(
    val memoryEnabledInAgent: Boolean = true,
    val items: List<AgentMemoryItem> = emptyList(),
    val lastDistilledAt: Long? = null,
)

internal data class MemoryProposal(
    val proposalId: String,
    val targetMemoryId: String?,
    val actionType: String, // "ADD" | "UPDATE"
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val sourceNoteIds: List<String> = emptyList(),
    val conflictsWithUserEdited: Boolean = false,
    val existingTitle: String? = null,
    val existingContent: String? = null,
)

internal class AgentMemoryStore(private val filesDir: File) {
    private val lock = Any()
    private val storeFile: File = File(filesDir, FILE_NAME)

    fun loadSnapshot(): AgentMemorySnapshot = synchronized(lock) {
        loadSnapshotLocked()
    }

    fun setGlobalEnabled(enabled: Boolean): AgentMemorySnapshot = synchronized(lock) {
        val current = loadSnapshotLocked()
        if (current.memoryEnabledInAgent == enabled) return@synchronized current
        val updated = current.copy(memoryEnabledInAgent = enabled)
        saveSnapshotLocked(updated)
        updated
    }

    fun addMemoryItem(
        category: MemoryCategory,
        title: String,
        content: String,
        sourceNoteIds: List<String> = emptyList(),
        enabled: Boolean = true,
        userEdited: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): AgentMemoryItem = synchronized(lock) {
        val cleanTitle = title.trim().ifEmpty { category.displayName }
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "记忆内容不能为空" }
        val current = loadSnapshotLocked()
        val item = AgentMemoryItem(
            id = newId(),
            category = category,
            title = safeUtf16Slice(cleanTitle, 0, 80),
            content = safeUtf16Slice(cleanContent, 0, 500),
            sourceNoteIds = sourceNoteIds.filter { it.isNotBlank() }.distinct(),
            enabled = enabled,
            userEdited = userEdited,
            updatedAt = nowMs,
        )
        val updated = current.copy(items = listOf(item) + current.items)
        saveSnapshotLocked(updated)
        item
    }

    fun updateMemoryItem(
        id: String,
        category: MemoryCategory? = null,
        title: String? = null,
        content: String? = null,
        enabled: Boolean? = null,
        markUserEdited: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): AgentMemoryItem? = synchronized(lock) {
        val current = loadSnapshotLocked()
        var updatedItem: AgentMemoryItem? = null
        val nextItems = current.items.map { item ->
            if (item.id != id) {
                item
            } else {
                val nextTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.let { safeUtf16Slice(it, 0, 80) } ?: item.title
                val nextContent = content?.trim()?.takeIf { it.isNotEmpty() }?.let { safeUtf16Slice(it, 0, 500) } ?: item.content
                val contentChanged = nextTitle != item.title || nextContent != item.content || (category != null && category != item.category)
                val next = item.copy(
                    category = category ?: item.category,
                    title = nextTitle,
                    content = nextContent,
                    enabled = enabled ?: item.enabled,
                    userEdited = if (markUserEdited && contentChanged) true else item.userEdited,
                    updatedAt = nowMs,
                )
                updatedItem = next
                next
            }
        }
        if (updatedItem != null) {
            saveSnapshotLocked(current.copy(items = nextItems))
        }
        updatedItem
    }

    fun setItemEnabled(
        id: String,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): AgentMemorySnapshot = synchronized(lock) {
        val current = loadSnapshotLocked()
        var changed = false
        val nextItems = current.items.map { item ->
            if (item.id == id && item.enabled != enabled) {
                changed = true
                item.copy(enabled = enabled, updatedAt = nowMs)
            } else {
                item
            }
        }
        if (!changed) return@synchronized current
        val updated = current.copy(items = nextItems)
        saveSnapshotLocked(updated)
        updated
    }

    fun deleteMemoryItem(id: String): AgentMemorySnapshot = synchronized(lock) {
        val current = loadSnapshotLocked()
        val nextItems = current.items.filterNot { it.id == id }
        if (nextItems.size == current.items.size) return@synchronized current
        val updated = current.copy(items = nextItems)
        saveSnapshotLocked(updated)
        updated
    }

    /**
     * Applies user-approved memory proposals atomically.
     * Core safety rule: items with `userEdited == true` are NEVER silently overwritten
     * unless explicitly authorized via `allowOverwriteUserEditedIds` (by item id or proposalId).
     */
    fun applyApprovedProposals(
        selectedProposals: List<MemoryProposal>,
        allowOverwriteUserEditedIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): AgentMemorySnapshot = synchronized(lock) {
        val current = loadSnapshotLocked()
        if (selectedProposals.isEmpty()) return@synchronized current

        val items = current.items.toMutableList()
        for (proposal in selectedProposals) {
            val cleanTitle = safeUtf16Slice(proposal.title.trim().ifEmpty { proposal.category.displayName }, 0, 80)
            val cleanContent = safeUtf16Slice(proposal.content.trim(), 0, 500)
            if (cleanContent.isEmpty()) continue
            val cleanSources = proposal.sourceNoteIds.filter { it.isNotBlank() }.distinct()

            val targetIdx = findMatchingItemIndex(items, proposal)
            if (targetIdx >= 0) {
                val existing = items[targetIdx]
                val authorizedOverwrite =
                    existing.id in allowOverwriteUserEditedIds ||
                        proposal.proposalId in allowOverwriteUserEditedIds
                if (existing.userEdited && !authorizedOverwrite) {
                    // Never overwrite user-edited memory without explicit confirmation.
                    continue
                }
                val mergedSources = (existing.sourceNoteIds + cleanSources).distinct()
                items[targetIdx] = existing.copy(
                    category = proposal.category,
                    title = cleanTitle,
                    content = cleanContent,
                    sourceNoteIds = mergedSources,
                    userEdited = false,
                    updatedAt = nowMs,
                )
            } else {
                items.add(
                    0,
                    AgentMemoryItem(
                        id = newId(),
                        category = proposal.category,
                        title = cleanTitle,
                        content = cleanContent,
                        sourceNoteIds = cleanSources,
                        enabled = true,
                        userEdited = false,
                        updatedAt = nowMs,
                    ),
                )
            }
        }

        val updated = current.copy(
            items = items,
            lastDistilledAt = nowMs,
        )
        saveSnapshotLocked(updated)
        updated
    }

    fun formatEnabledMemoryForPrompt(maxChars: Int = DEFAULT_PROMPT_MEMORY_CHARS): String {
        val budget = maxChars.coerceAtLeast(0)
        if (budget == 0) return ""
        val snapshot = loadSnapshot()
        if (!snapshot.memoryEnabledInAgent) return ""
        val enabledItems = snapshot.items.filter { it.enabled && it.content.isNotBlank() }
        if (enabledItems.isEmpty()) return ""

        val sb = StringBuilder()
        for (item in enabledItems) {
            val safeTitle = LocalAgentEngine.sanitizeQuoteText(item.title)
            val safeBody = LocalAgentEngine.sanitizeQuoteText(item.content)
            val line = "- [${item.category.displayName}] $safeTitle：$safeBody"
            val sep = if (sb.isEmpty()) 0 else 1
            if (sb.length + sep + line.length <= budget) {
                if (sep > 0) sb.append('\n')
                sb.append(line)
            } else {
                val remaining = budget - sb.length - sep
                if (remaining >= 16) {
                    if (sep > 0) sb.append('\n')
                    sb.append(safeUtf16Slice(line, 0, remaining))
                }
                break
            }
        }
        return sb.toString()
    }

    private fun findMatchingItemIndex(items: List<AgentMemoryItem>, proposal: MemoryProposal): Int {
        val targetId = proposal.targetMemoryId?.takeIf { it.isNotBlank() }
        if (targetId != null) {
            val byId = items.indexOfFirst { it.id == targetId }
            if (byId >= 0) return byId
        }
        val normTitle = normalizeMemoryTitle(proposal.title)
        if (normTitle.isEmpty()) return -1
        return items.indexOfFirst { normalizeMemoryTitle(it.title) == normTitle }
    }

    private fun loadSnapshotLocked(): AgentMemorySnapshot {
        if (!storeFile.isFile) return AgentMemorySnapshot()
        return runCatching {
            val root = MiniJson.parseObject(storeFile.readText(Charsets.UTF_8))
            val enabled = root["memory_enabled_in_agent"] as? Boolean ?: true
            val lastDistilled = (root["last_distilled_at"] as? Number)?.toLong()
            val rawItems = root["items"] as? List<*> ?: emptyList<Any?>()
            val items = rawItems.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val obj = entry as? Map<String, Any?> ?: return@mapNotNull null
                val id = obj["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = obj["title"]?.toString().orEmpty()
                val content = obj["content"]?.toString().orEmpty()
                if (content.isBlank()) return@mapNotNull null
                val sources = (obj["source_note_ids"] as? List<*>)
                    ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                    ?.distinct()
                    .orEmpty()
                AgentMemoryItem(
                    id = id,
                    category = MemoryCategory.fromId(obj["category"]?.toString()),
                    title = title,
                    content = content,
                    sourceNoteIds = sources,
                    enabled = obj["enabled"] as? Boolean ?: true,
                    userEdited = obj["user_edited"] as? Boolean ?: false,
                    updatedAt = (obj["updated_at"] as? Number)?.toLong() ?: 0L,
                )
            }
            AgentMemorySnapshot(
                memoryEnabledInAgent = enabled,
                items = items,
                lastDistilledAt = lastDistilled,
            )
        }.getOrElse { AgentMemorySnapshot() }
    }

    private fun saveSnapshotLocked(snapshot: AgentMemorySnapshot) {
        val encoded = MiniJson.encode(
            linkedMapOf(
                "schema_version" to 1,
                "memory_enabled_in_agent" to snapshot.memoryEnabledInAgent,
                "last_distilled_at" to snapshot.lastDistilledAt,
                "items" to snapshot.items.map { item ->
                    linkedMapOf(
                        "id" to item.id,
                        "category" to item.category.id,
                        "title" to item.title,
                        "content" to item.content,
                        "source_note_ids" to item.sourceNoteIds,
                        "enabled" to item.enabled,
                        "user_edited" to item.userEdited,
                        "updated_at" to item.updatedAt,
                    )
                },
            ),
        )
        writeFileAtomically(storeFile, encoded)
    }

    companion object {
        private const val FILE_NAME = "agent_memory.json"
        const val DEFAULT_PROMPT_MEMORY_CHARS = 1200

        internal fun normalizeMemoryTitle(raw: String): String = buildString {
            for (ch in raw.lowercase(Locale.ROOT).trim()) {
                if (ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF) {
                    append(ch)
                }
            }
        }
    }
}

internal data class AgentPreset(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val builtIn: Boolean,
    val defaultDays: Int? = 30,
    val category: String = "flomo",
)

internal class AgentPresetStore(private val filesDir: File) {
    private val lock = Any()
    private val storeFile: File = File(filesDir, FILE_NAME)

    fun loadPresets(): List<AgentPreset> = synchronized(lock) {
        BUILT_IN_PRESETS + loadCustomPresetsLocked()
    }

    fun getPreset(id: String): AgentPreset? = loadPresets().firstOrNull { it.id == id }

    fun saveCustomPreset(
        name: String,
        description: String,
        systemPrompt: String,
        defaultDays: Int? = 30,
        id: String? = null,
        category: String = "custom",
    ): AgentPreset = synchronized(lock) {
        val cleanName = name.trim()
        val cleanPrompt = systemPrompt.trim()
        require(cleanName.isNotEmpty()) { "Agent 名称不能为空" }
        require(cleanPrompt.isNotEmpty()) { "Agent 提示词不能为空" }
        val cleanDesc = description.trim().ifEmpty { safeUtf16Slice(cleanPrompt, 0, 60) }
        val cleanDays = defaultDays?.takeIf { it > 0 }
        val cleanCategory = category.trim().ifEmpty { "custom" }

        if (id != null && id in BUILT_IN_IDS) {
            throw IllegalArgumentException("内置预设不可修改或覆盖: $id")
        }

        val custom = loadCustomPresetsLocked().toMutableList()
        val targetId = id?.trim()?.takeIf { it.isNotEmpty() } ?: "custom_${newId()}"
        val saved = AgentPreset(
            id = targetId,
            name = safeUtf16Slice(cleanName, 0, 40),
            description = safeUtf16Slice(cleanDesc, 0, 120),
            systemPrompt = safeUtf16Slice(cleanPrompt, 0, 2000),
            builtIn = false,
            defaultDays = cleanDays,
            category = cleanCategory,
        )
        val existingIdx = custom.indexOfFirst { it.id == targetId }
        if (existingIdx >= 0) {
            custom[existingIdx] = saved
        } else {
            custom.add(0, saved)
        }
        saveCustomPresetsLocked(custom)
        saved
    }

    fun deleteCustomPreset(id: String): Boolean = synchronized(lock) {
        if (id in BUILT_IN_IDS) return@synchronized false
        val custom = loadCustomPresetsLocked()
        val filtered = custom.filterNot { it.id == id }
        if (filtered.size == custom.size) return@synchronized false
        saveCustomPresetsLocked(filtered)
        true
    }

    private fun loadCustomPresetsLocked(): List<AgentPreset> {
        if (!storeFile.isFile) return emptyList()
        return runCatching {
            val root = MiniJson.parseObject(storeFile.readText(Charsets.UTF_8))
            val raw = root["custom_presets"] as? List<*> ?: return@runCatching emptyList()
            raw.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val obj = entry as? Map<String, Any?> ?: return@mapNotNull null
                val id = obj["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (id in BUILT_IN_IDS) return@mapNotNull null
                val name = obj["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val prompt = obj["system_prompt"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                AgentPreset(
                    id = id,
                    name = name,
                    description = obj["description"]?.toString().orEmpty(),
                    systemPrompt = prompt,
                    builtIn = false,
                    defaultDays = (obj["default_days"] as? Number)?.toInt()?.takeIf { it > 0 },
                    category = obj["category"]?.toString()?.ifBlank { "custom" } ?: "custom",
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveCustomPresetsLocked(custom: List<AgentPreset>) {
        val encoded = MiniJson.encode(
            linkedMapOf(
                "schema_version" to 1,
                "custom_presets" to custom.map { preset ->
                    linkedMapOf(
                        "id" to preset.id,
                        "name" to preset.name,
                        "description" to preset.description,
                        "system_prompt" to preset.systemPrompt,
                        "built_in" to false,
                        "default_days" to preset.defaultDays,
                        "category" to preset.category,
                    )
                },
            ),
        )
        writeFileAtomically(storeFile, encoded)
    }

    companion object {
        private const val FILE_NAME = "agent_presets.json"

        const val PRESET_BLINDSPOTS = "flomo_blindspots"
        const val PRESET_SHARP_QUESTIONS = "flomo_sharp_questions"
        const val PRESET_STANCE_SHIFT = "flomo_stance_shift"
        const val PRESET_MEANING_RADAR = "flomo_meaning_radar"

        val BUILT_IN_PRESETS: List<AgentPreset> = listOf(
            AgentPreset(
                id = PRESET_BLINDSPOTS,
                name = "找思维盲点",
                description = "检查近 30 天笔记的思维盲区、隐含假设与被忽略的反面视角",
                systemPrompt = "请先检索并阅读用户近期笔记，找出反复出现但未经验证的隐含假设、思维盲区以及被忽略的反面视角。" +
                    "只允许引用真实读到的笔记 [note_id]，客观指出盲点与可验证的反例，不要替用户空洞说教。",
                builtIn = true,
                defaultDays = 30,
                category = "flomo",
            ),
            AgentPreset(
                id = PRESET_SHARP_QUESTIONS,
                name = "3 个尖锐追问",
                description = "只基于用户真实记录提出 3 个直击本质的追问，不替用户编造结论",
                systemPrompt = "请先检索并阅读用户近期真实记录，仅基于具体记录内容，提出 3 个直击问题本质、能促使深入思考的尖锐追问。" +
                    "每个追问必须附带真实引用的笔记 [note_id] 与触发原因，不要替用户编造答案或结论。",
                builtIn = true,
                defaultDays = 30,
                category = "flomo",
            ),
            AgentPreset(
                id = PRESET_STANCE_SHIFT,
                name = "观点变化对比",
                description = "对比早期与近期在类似主题上的态度、精力或关注重点迁移",
                systemPrompt = "请搜索用户不同时间段的同类主题笔记，对比早期与近期在观点立场、精力投入、关注重点或顾虑上的具体迁移。" +
                    "明确列出早期与近期的对应笔记 [note_id] 作为对照证据，避免臆测。",
                builtIn = true,
                defaultDays = 90,
                category = "flomo",
            ),
            AgentPreset(
                id = PRESET_MEANING_RADAR,
                name = "意义雷达",
                description = "识别反复出现的情绪触点、内耗卡点与真正让你兴奋的信号",
                systemPrompt = "请检索并阅读用户近期记录，像一面诚实的镜子一样识别：1) 反复消耗精力的内耗卡点与情绪触点；" +
                    "2) 真正让用户兴奋、产生心流或持续投入的积极信号。所有判断必须绑定真实笔记 [note_id]。",
                builtIn = true,
                defaultDays = 30,
                category = "flomo",
            ),
        )

        private val BUILT_IN_IDS = BUILT_IN_PRESETS.mapTo(HashSet()) { it.id }
    }
}

internal fun writeFileAtomically(target: File, content: String) {
    val parent = target.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
    val tmp = File(parent ?: File("."), "${target.name}.${newId().take(8)}.tmp")
    try {
        tmp.writeText(content, Charsets.UTF_8)
        val movedAtomically = runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (!movedAtomically) {
            val moved = runCatching {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
            if (!moved) {
                tmp.copyTo(target, overwrite = true)
            }
        }
    } finally {
        if (tmp.exists()) {
            runCatching { tmp.delete() }
        }
    }
}
