package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.mediaFlags
import io.github.z3275630ops.floatingnotes.data.local.tagSubtree
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

internal data class RetrievalCoverage(
    val eligibleCount: Int,
    val indexedCount: Int,
    val staleCount: Int,
    val indexWatermark: String?,
) {
    val isComplete: Boolean get() = staleCount == 0
}

internal data class EvidenceHit(
    val note: NoteEntity,
    val noteTitle: String,
    val mergedExcerpt: String,
    val matchedBlockId: String?,
    val chunkIds: List<String>,
    val reasons: List<String>,
    val tagNames: List<String>,
    val score: Double,
)

internal data class FindSearchResult(
    val query: String,
    val hits: List<EvidenceHit>,
    val coverage: RetrievalCoverage,
    val extractedKeywords: List<String>,
)

internal data class RelatedNoteMatch(
    val note: NoteEntity,
    val noteTitle: String,
    val previewExcerpt: String,
    val targetBlockId: String?,
    val reasons: List<String>,
    val score: Double,
)

internal data class EvidenceBasketItem(
    val noteId: String,
    val noteRevision: Long,
    val noteTitle: String,
    val excerpt: String,
    val blockId: String?,
    val reasons: List<String>,
)

internal object LocalRetrievalEngine {
    private val STOP_PHRASES = listOf(
        "有哪些", "有什么", "什么时候", "为什么", "怎么样", "怎么办", "怎么",
        "哪些", "什么", "哪里", "以前", "曾经", "关于", "的时候", "时候",
        "一下", "可以", "应该", "觉得", "让我", "我们", "自己", "这个", "那个",
        "以及", "或者", "还有", "如果", "所以", "但是", "因为", "里面", "内容",
        "笔记", "记录", "写过", "说过", "提到", "经历", "事项", "东西", "地方",
    )
    private val STOP_CHARS = setOf(
        '的', '了', '和', '与', '在', '是', '有', '我', '你', '他', '她', '它',
        '吗', '呢', '啊', '吧', '呀', '哦', '呀', '都', '也', '就', '还', '很',
        '把', '被', '给', '从', '到', '向', '对', '为', '而', '着', '过', '这', '那',
        '里', '中', '上', '下', '更', '最', '曾', '可', '能', '会', '要', '想',
    )
    private val ASCII_STOP = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and",
        "or", "in", "on", "at", "for", "with", "about", "what", "when", "where",
        "why", "how", "which", "who", "my", "me", "i", "you", "it", "this", "that",
    )

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    fun tokenizeForIndex(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = LinkedHashSet<String>()
        val lower = text.lowercase(Locale.ROOT)
        val asciiRegex = Regex("[a-z0-9_.-]{2,}")
        for (match in asciiRegex.findAll(lower)) {
            val tok = match.value.trim('.', '-', '_')
            if (tok.length >= 2 && tok !in ASCII_STOP) {
                tokens.add(tok)
            }
        }
        val cjkRun = StringBuilder()
        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val s = cjkRun.toString()
            cjkRun.setLength(0)
            for (i in s.indices) {
                val c = s[i]
                if (c !in STOP_CHARS) {
                    tokens.add(c.toString())
                }
                if (i + 1 < s.length) {
                    tokens.add(s.substring(i, i + 2))
                }
                if (i + 2 < s.length) {
                    tokens.add(s.substring(i, i + 3))
                }
            }
        }
        for (ch in lower) {
            if (isCjk(ch)) {
                cjkRun.append(ch)
            } else {
                flushCjk()
            }
        }
        flushCjk()
        return tokens.toList()
    }

    fun extractQueryTerms(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        var cleaned = trimmed.lowercase(Locale.ROOT)
        for (phrase in STOP_PHRASES) {
            cleaned = cleaned.replace(phrase, " ")
        }
        val weightedTerms = LinkedHashSet<String>()
        val asciiRegex = Regex("[a-z0-9_.-]{2,}")
        for (match in asciiRegex.findAll(cleaned)) {
            val tok = match.value.trim('.', '-', '_')
            if (tok.length >= 2 && tok !in ASCII_STOP) weightedTerms.add(tok)
        }
        val cjkRun = StringBuilder()
        fun flushRun() {
            if (cjkRun.isEmpty()) return
            val s = cjkRun.toString().filter { it !in STOP_CHARS }
            cjkRun.setLength(0)
            if (s.isEmpty()) return
            if (s.length == 1) {
                weightedTerms.add(s)
                return
            }
            for (i in 0 until s.length - 1) {
                weightedTerms.add(s.substring(i, i + 2))
            }
            for (i in 0 until s.length - 2) {
                weightedTerms.add(s.substring(i, i + 3))
            }
            if (s.length in 2..6) {
                weightedTerms.add(s)
            }
        }
        for (ch in cleaned) {
            if (isCjk(ch)) cjkRun.append(ch) else flushRun()
        }
        flushRun()
        if (weightedTerms.isEmpty()) {
            return tokenizeForIndex(trimmed).take(8)
        }
        return weightedTerms.toList()
    }

    fun buildChunks(note: NoteEntity): List<NoteChunkEntity> {
        if (note.deletedAt != null) return emptyList()
        val doc = runCatching { NoteDocument.decode(note.documentJson) }.getOrNull()
        val rawSegments = if (doc != null && doc.blocks.isNotEmpty()) {
            doc.blocks.flatMap { block ->
                val text = block.searchableText.trim()
                if (text.isEmpty()) emptyList()
                else splitLongSegment(text, 220).map { part -> block.id to part }
            }
        } else {
            val fallback = note.searchText.trim()
            if (fallback.isEmpty()) emptyList()
            else splitLongSegment(fallback, 220).map { part -> "body" to part }
        }

        val title = note.title?.trim().orEmpty()
        if (rawSegments.isEmpty() && title.isEmpty()) return emptyList()

        val result = ArrayList<NoteChunkEntity>()
        val currentBlockIds = ArrayList<String>()
        val currentText = StringBuilder()
        var order = 0

        fun flushChunk(includeTitle: Boolean) {
            val bodyText = currentText.toString().trim()
            val combinedForTokens = if (includeTitle && title.isNotEmpty()) "$title\n$bodyText" else bodyText
            val excerpt = if (bodyText.isNotEmpty()) safeUtf16Slice(bodyText, 0, 260) else safeUtf16Slice(title, 0, 120)
            if (excerpt.isBlank()) return
            val tokens = tokenizeForIndex(combinedForTokens)
            val idsJson = encodeStringArrayJson(currentBlockIds.ifEmpty { listOf("title") })
            result.add(
                NoteChunkEntity(
                    id = "${note.id}#chunk_$order",
                    noteId = note.id,
                    chunkOrder = order,
                    blockIdsJson = idsJson,
                    noteRevision = note.revision,
                    contentHash = note.contentHash,
                    excerpt = excerpt,
                    tokenPayload = tokens.joinToString(" "),
                    updatedAt = note.updatedAt,
                ),
            )
            order += 1
            currentBlockIds.clear()
            currentText.setLength(0)
        }

        if (rawSegments.isEmpty() && title.isNotEmpty()) {
            flushChunk(includeTitle = true)
            return result
        }

        for ((blockId, segmentText) in rawSegments) {
            if (currentText.isNotEmpty() && currentText.length + segmentText.length > 220) {
                flushChunk(includeTitle = order == 0)
            }
            if (currentText.isNotEmpty()) currentText.append('\n')
            currentText.append(segmentText)
            currentBlockIds.add(blockId)
        }
        if (currentText.isNotEmpty()) {
            flushChunk(includeTitle = order == 0)
        }
        return result
    }

    private fun splitLongSegment(text: String, maxChars: Int = 220): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val parts = ArrayList<String>()
        var start = 0
        val minSplitSpan = maxOf(maxChars / 2, 1)
        while (start < text.length) {
            val rawEnd = (start + maxChars).coerceAtMost(text.length)
            var candidateEnd = rawEnd
            if (rawEnd < text.length) {
                val searchFrom = (start + minSplitSpan).coerceAtMost(rawEnd)
                for (pos in rawEnd - 1 downTo searchFrom) {
                    val c = text[pos]
                    if (c.isWhitespace() || c in "。！？；.!?;，,、\n") {
                        candidateEnd = pos + 1
                        break
                    }
                }
            }
            var slice = safeUtf16Slice(text, start, candidateEnd)
            if (slice.isEmpty() && start < text.length) {
                slice = safeUtf16Slice(text, start, (start + 2).coerceAtMost(text.length))
                if (slice.isEmpty()) break
            }
            val nextStart = maxOf(start + slice.length, start + 1)
            val cleaned = slice.trim()
            if (cleaned.isNotEmpty()) {
                parts.add(cleaned)
            }
            start = nextStart
        }
        return parts
    }

    private fun freshChunksFor(
        note: NoteEntity,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
    ): List<NoteChunkEntity> {
        val cached = chunksByNote[note.id]
        if (!cached.isNullOrEmpty() && cached.all { it.noteRevision == note.revision && it.contentHash == note.contentHash }) {
            return cached
        }
        return buildChunks(note)
    }

    fun isScopeEligible(
        note: NoteEntity,
        scope: NoteScope,
        noteTagIds: Set<String>,
        noteNotebookIds: Set<String>,
        expandedIncludeTags: Set<String>,
        expandedExcludeTags: Set<String>,
        excludedNoteIds: Set<String>,
        expandedIncludeTagGroups: List<Set<String>> = emptyList(),
    ): Boolean {
        if (note.deletedAt != null) return false
        if (note.id in excludedNoteIds) return false
        if (!scope.archived && note.archivedAt != null) return false
        if (scope.aiOnly && note.aiPolicy !in setOf("inherit", "allow")) return false
        if (scope.noteIds.isNotEmpty() && note.id !in scope.noteIds) return false
        if (scope.sources.isNotEmpty() && note.sourceType !in scope.sources) return false
        if (scope.notebooks.isNotEmpty() && noteNotebookIds.none { it in scope.notebooks }) return false
        if (scope.media.isNotEmpty()) {
            val requiredMask = scope.media.fold(0) { bits, type -> bits or (mediaFlags[type] ?: 0) }
            if ((note.mediaMask and requiredMask) == 0) return false
        }
        if (scope.untagged && noteTagIds.isNotEmpty()) return false
        if (scope.includeTags.isNotEmpty()) {
            if (scope.matchAll) {
                if (expandedIncludeTagGroups.isNotEmpty()) {
                    if (!expandedIncludeTagGroups.all { group -> noteTagIds.any { it in group } }) return false
                } else if (!scope.includeTags.all { it in noteTagIds }) {
                    return false
                }
            } else {
                if (noteTagIds.none { it in expandedIncludeTags }) return false
            }
        }
        if (expandedExcludeTags.isNotEmpty() && noteTagIds.any { it in expandedExcludeTags }) return false
        val axisStr = if (scope.updatedTime) note.updatedAt else note.createdAt
        val axisInstant = runCatching { Instant.parse(axisStr) }.getOrNull()
        if (scope.from != null && (axisInstant == null || axisInstant < scope.from)) return false
        if (scope.until != null && (axisInstant == null || axisInstant >= scope.until)) return false
        return true
    }

    fun expandTagGroups(ids: Set<String>, allTags: List<TagEntity>, descendants: Boolean): List<Set<String>> {
        if (ids.isEmpty()) return emptyList()
        return ids.map { id ->
            if (descendants) tagSubtree(allTags, id) else setOf(id)
        }
    }

    fun expandTagSelection(ids: Set<String>, allTags: List<TagEntity>, descendants: Boolean): Set<String> {
        if (ids.isEmpty()) return emptySet()
        if (!descendants) return ids
        val expanded = HashSet<String>()
        for (id in ids) {
            expanded.addAll(tagSubtree(allTags, id))
        }
        return expanded
    }

    fun computeCoverage(
        eligibleNotes: List<NoteEntity>,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
    ): RetrievalCoverage {
        if (eligibleNotes.isEmpty()) {
            return RetrievalCoverage(
                eligibleCount = 0,
                indexedCount = 0,
                staleCount = 0,
                indexWatermark = null,
            )
        }
        var indexed = 0
        var latestWatermark: String? = null
        for (note in eligibleNotes) {
            val chunks = chunksByNote[note.id].orEmpty()
            val hasContent = note.title?.isNotBlank() == true || note.searchText.isNotBlank()
            val fresh = if (!hasContent) {
                true
            } else {
                chunks.isNotEmpty() && chunks.all { it.noteRevision == note.revision && it.contentHash == note.contentHash }
            }
            if (fresh) {
                indexed += 1
                val wm = chunks.maxOfOrNull { it.updatedAt } ?: note.updatedAt
                if (latestWatermark == null || wm > latestWatermark) {
                    latestWatermark = wm
                }
            }
        }
        return RetrievalCoverage(
            eligibleCount = eligibleNotes.size,
            indexedCount = indexed,
            staleCount = (eligibleNotes.size - indexed).coerceAtLeast(0),
            indexWatermark = latestWatermark,
        )
    }

    fun searchEvidence(
        query: String,
        scope: NoteScope,
        allNotes: List<NoteEntity>,
        allChunks: List<NoteChunkEntity>,
        tags: List<TagEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        noteNotebookIdsByNote: Map<String, Set<String>>,
        excludedNoteIds: Set<String> = emptySet(),
        limit: Int = 20,
    ): FindSearchResult {
        val tagsById = tags.associateBy { it.id }
        val expandedInclude = expandTagSelection(scope.includeTags, tags, scope.descendants)
        val expandedIncludeGroups = expandTagGroups(scope.includeTags, tags, scope.descendants)
        val expandedExclude = expandTagSelection(scope.excludeTags, tags, scope.descendants)
        val eligibleNotes = allNotes.filter { note ->
            isScopeEligible(
                note = note,
                scope = scope,
                noteTagIds = noteTagIdsByNote[note.id].orEmpty(),
                noteNotebookIds = noteNotebookIdsByNote[note.id].orEmpty(),
                expandedIncludeTags = expandedInclude,
                expandedExcludeTags = expandedExclude,
                excludedNoteIds = excludedNoteIds,
                expandedIncludeTagGroups = expandedIncludeGroups,
            )
        }
        val chunksByNote = allChunks.groupBy { it.noteId }
        val coverage = computeCoverage(eligibleNotes, chunksByNote)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return FindSearchResult(
                query = trimmed,
                hits = emptyList(),
                coverage = coverage,
                extractedKeywords = emptyList(),
            )
        }
        val queryTerms = extractQueryTerms(trimmed)
        val rawLower = trimmed.lowercase(Locale.ROOT)
        val docFreq = HashMap<String, Int>()
        val eligibleChunksByNote = HashMap<String, List<NoteChunkEntity>>(eligibleNotes.size)
        for (note in eligibleNotes) {
            val chunks = freshChunksFor(note, chunksByNote)
            eligibleChunksByNote[note.id] = chunks
            val seenInNote = HashSet<String>()
            for (chunk in chunks) {
                for (tok in chunk.tokenPayload.split(' ')) {
                    if (tok.isNotEmpty() && seenInNote.add(tok)) {
                        docFreq[tok] = (docFreq[tok] ?: 0) + 1
                    }
                }
            }
        }
        val totalDocs = eligibleNotes.size.coerceAtLeast(1)
        val isPinyinQuery = LocalFuzzyMatcher.isAsciiPinyinQuery(trimmed)

        val hits = ArrayList<EvidenceHit>()
        for (note in eligibleNotes) {
            val chunks = eligibleChunksByNote[note.id].orEmpty()
            val tagIds = noteTagIdsByNote[note.id].orEmpty()
            val tagNames = tagIds.mapNotNull { tagsById[it]?.name }.sorted()
            val titleText = note.title?.trim().orEmpty()
            val titleLower = titleText.lowercase(Locale.ROOT)
            val bodyLower = note.searchText.lowercase(Locale.ROOT)

            var titleBonus = 0.0
            val matchedTerms = LinkedHashSet<String>()
            var matchedPinyin = false
            var matchedFuzzy = false

            if (titleLower.contains(rawLower)) {
                titleBonus += 6.0
                matchedTerms.add(trimmed)
            }
            for (term in queryTerms) {
                if (titleLower.contains(term)) {
                    titleBonus += if (term.length >= 2) 2.6 else 0.9
                    matchedTerms.add(term)
                }
            }
            if (isPinyinQuery && titleText.isNotEmpty() && !titleLower.contains(rawLower)) {
                val pinyinRes = LocalFuzzyMatcher.matchPinyin(trimmed, titleText)
                if (pinyinRes.matched) {
                    titleBonus += 5.0 * pinyinRes.score
                    matchedPinyin = true
                    pinyinRes.matchedSnippet?.let { matchedTerms.add(it) }
                }
            }

            var tagBonus = 0.0
            for (tagName in tagNames) {
                val tl = tagName.lowercase(Locale.ROOT)
                if (rawLower.contains(tl) || queryTerms.any { it.length >= 2 && tl.contains(it) }) {
                    tagBonus += 3.0
                    matchedTerms.add("#$tagName")
                } else if (isPinyinQuery && LocalFuzzyMatcher.matchPinyin(trimmed, tagName).matched) {
                    tagBonus += 3.5
                    matchedPinyin = true
                    matchedTerms.add("#$tagName")
                }
            }

            val scoredChunks = ArrayList<Pair<NoteChunkEntity, Double>>()
            for (chunk in chunks) {
                val tokenSet = chunk.tokenPayload.split(' ').filter { it.isNotEmpty() }.toHashSet()
                val excerptLower = chunk.excerpt.lowercase(Locale.ROOT)
                var chunkScore = 0.0
                if (excerptLower.contains(rawLower)) {
                    chunkScore += 5.5
                }
                for (term in queryTerms) {
                    val weight = when {
                        term.length >= 3 -> 2.2
                        term.length == 2 -> 1.5
                        else -> 0.55
                    }
                    val df = docFreq[term] ?: 0
                    val idf = ln(1.0 + (totalDocs.toDouble() + 0.5) / (df + 0.5))
                    val inTokens = term in tokenSet
                    val inExcerpt = term.length >= 2 && excerptLower.contains(term)
                    if (inTokens || inExcerpt) {
                        chunkScore += weight * idf + if (inExcerpt) 0.8 else 0.0
                        matchedTerms.add(term)
                    }
                }
                if (isPinyinQuery && !excerptLower.contains(rawLower)) {
                    val pinyinRes = LocalFuzzyMatcher.matchPinyin(trimmed, chunk.excerpt)
                    if (pinyinRes.matched) {
                        chunkScore += 3.2 * pinyinRes.score
                        matchedPinyin = true
                        pinyinRes.matchedSnippet?.let { matchedTerms.add(it) }
                    }
                }
                if (chunkScore > 0.35) {
                    scoredChunks.add(chunk to chunkScore)
                }
            }

            if (scoredChunks.isEmpty() && titleBonus <= 0.0 && tagBonus <= 0.0 && !bodyLower.contains(rawLower)) {
                if (titleText.isNotEmpty()) {
                    val fuzzyTitle = LocalFuzzyMatcher.matchFuzzyText(trimmed, titleText)
                    if (fuzzyTitle.matched && fuzzyTitle.kind == FuzzyMatchKind.EDIT_DISTANCE) {
                        titleBonus += 3.6 * fuzzyTitle.score
                        matchedFuzzy = true
                        fuzzyTitle.matchedSnippet?.let { matchedTerms.add(it) }
                    }
                }
                for (chunk in chunks) {
                    val fuzzyChunk = LocalFuzzyMatcher.matchFuzzyText(trimmed, chunk.excerpt)
                    if (fuzzyChunk.matched && fuzzyChunk.kind == FuzzyMatchKind.EDIT_DISTANCE) {
                        val chunkScore = 2.6 * fuzzyChunk.score
                        if (chunkScore > 0.35) {
                            scoredChunks.add(chunk to chunkScore)
                            matchedFuzzy = true
                            fuzzyChunk.matchedSnippet?.let { matchedTerms.add(it) }
                        }
                    }
                }
            }

            if (scoredChunks.isEmpty() && titleBonus <= 0.0 && tagBonus <= 0.0 && !bodyLower.contains(rawLower)) {
                continue
            }

            val topChunks = scoredChunks
                .sortedWith(compareByDescending<Pair<NoteChunkEntity, Double>> { it.second }.thenBy { it.first.chunkOrder })
                .take(3)
                .map { it.first }
                .sortedBy { it.chunkOrder }

            val mergedExcerpt = if (topChunks.isNotEmpty()) {
                mergeAdjacentChunks(topChunks)
            } else {
                safeUtf16Slice(note.searchText.ifBlank { titleText }, 0, 220)
            }
            val firstBlockId = topChunks.firstOrNull()?.let { parseFirstBlockId(it.blockIdsJson) }
            val chunkTotalScore = scoredChunks.sumOf { it.second } * 0.75 + (scoredChunks.maxOfOrNull { it.second } ?: 0.0)
            val totalScore = chunkTotalScore + titleBonus + tagBonus + if (bodyLower.contains(rawLower)) 3.0 else 0.0

            if (totalScore < 0.8) continue

            val reasons = ArrayList<String>()
            if (excerptContainsExact(mergedExcerpt, titleText, rawLower)) {
                reasons.add("原句命中")
            }
            if (matchedPinyin) {
                reasons.add("拼音匹配")
            }
            if (matchedFuzzy) {
                reasons.add("近似拼写匹配")
            }
            if (tagBonus > 0.0) {
                reasons.add("标签匹配")
            }
            val termHits = matchedTerms.filterNot { it.startsWith("#") }.take(3)
            if (termHits.isNotEmpty()) {
                reasons.add("关键词：${termHits.joinToString("、")}")
            }
            if (topChunks.size > 1) {
                reasons.add("已合并 ${topChunks.size} 段相关正文")
            }
            if (reasons.isEmpty()) reasons.add("语义近邻匹配")

            hits.add(
                EvidenceHit(
                    note = note,
                    noteTitle = displayNoteTitle(note),
                    mergedExcerpt = mergedExcerpt,
                    matchedBlockId = firstBlockId,
                    chunkIds = topChunks.map { it.id },
                    reasons = reasons,
                    tagNames = tagNames,
                    score = totalScore,
                ),
            )
        }

        val sorted = hits
            .sortedWith(compareByDescending<EvidenceHit> { it.score }.thenByDescending { it.note.updatedAt })
            .take(limit)

        return FindSearchResult(
            query = trimmed,
            hits = sorted,
            coverage = coverage,
            extractedKeywords = queryTerms.filter { it.length >= 2 }.take(6),
        )
    }

    fun mergeAdjacentChunks(sortedChunks: List<NoteChunkEntity>): String {
        if (sortedChunks.isEmpty()) return ""
        val sb = StringBuilder()
        var lastOrder: Int? = null
        for (chunk in sortedChunks) {
            val text = chunk.excerpt.trim()
            if (text.isEmpty()) continue
            if (sb.isNotEmpty()) {
                if (lastOrder != null && chunk.chunkOrder == lastOrder + 1) {
                    sb.append('\n')
                } else {
                    sb.append("\n…\n")
                }
            }
            sb.append(text)
            lastOrder = chunk.chunkOrder
        }
        return safeUtf16Slice(sb.toString(), 0, 420)
    }

    fun computeRelatedNotes(
        sourceNote: NoteEntity,
        allNotes: List<NoteEntity>,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
        tags: List<TagEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        noteNotebookIdsByNote: Map<String, Set<String>>,
        links: List<NoteLinkEntity>,
        excludedNoteIds: Set<String> = emptySet(),
        includeArchived: Boolean = false,
        now: Instant = Instant.now(),
        limit: Int = 8,
    ): List<RelatedNoteMatch> {
        val tagsById = tags.associateBy { it.id }
        val sourceTagIds = noteTagIdsByNote[sourceNote.id].orEmpty()
        val sourceAncestors = ancestorTagIds(sourceTagIds, tagsById)
        val sourceNotebooks = noteNotebookIdsByNote[sourceNote.id].orEmpty()
        val outgoingTargets = links.filter { it.sourceId == sourceNote.id }.mapTo(HashSet()) { it.targetId }
        val incomingSources = links.filter { it.targetId == sourceNote.id }.mapTo(HashSet()) { it.sourceId }
        val sourceInstant = runCatching { Instant.parse(sourceNote.updatedAt) }.getOrElse { now }

        val activeCorpus = ArrayList<NoteEntity>(allNotes.size + 1)
        val seenCorpusIds = HashSet<String>(allNotes.size + 1)
        if (seenCorpusIds.add(sourceNote.id)) {
            activeCorpus.add(sourceNote)
        }
        for (note in allNotes) {
            if (note.deletedAt != null) continue
            if (note.id in excludedNoteIds) continue
            if (!includeArchived && note.archivedAt != null) continue
            if (seenCorpusIds.add(note.id)) {
                activeCorpus.add(note)
            }
        }

        val tokensByNote = HashMap<String, Set<String>>(activeCorpus.size)
        val df = HashMap<String, Int>()
        for (note in activeCorpus) {
            val tokens = tokensForNote(note, freshChunksFor(note, chunksByNote))
            tokensByNote[note.id] = tokens
            for (tok in tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val totalDocs = activeCorpus.size.coerceAtLeast(1)
        fun idfOf(tok: String): Double {
            val count = df[tok] ?: 0
            return ln(1.0 + (totalDocs.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        val sourceTokens = tokensByNote[sourceNote.id].orEmpty()
        val sourceNorm = vectorNorm(sourceTokens, ::idfOf)

        val matches = ArrayList<RelatedNoteMatch>()
        for (candidate in allNotes) {
            if (candidate.id == sourceNote.id) continue
            if (candidate.deletedAt != null) continue
            if (candidate.id in excludedNoteIds) continue
            if (!includeArchived && candidate.archivedAt != null) continue

            val candidateChunks = freshChunksFor(candidate, chunksByNote)
            val candidateTokens = tokensByNote[candidate.id] ?: tokensForNote(candidate, candidateChunks)
            val candidateTagIds = noteTagIdsByNote[candidate.id].orEmpty()
            val candidateAncestors = ancestorTagIds(candidateTagIds, tagsById)
            val candidateNotebooks = noteNotebookIdsByNote[candidate.id].orEmpty()

            var score = 0.0
            val reasons = ArrayList<String>()

            val isOut = candidate.id in outgoingTargets
            val isIn = candidate.id in incomingSources
            if (isOut && isIn) {
                score += 7.5
                reasons.add("双向引用")
            } else if (isOut) {
                score += 6.0
                reasons.add("正文已引用")
            } else if (isIn) {
                score += 6.0
                reasons.add("被此笔记引用")
            }

            val directSharedTags = sourceTagIds.intersect(candidateTagIds)
            if (directSharedTags.isNotEmpty()) {
                score += 3.5 + min(directSharedTags.size - 1, 2) * 1.0
                val label = directSharedTags.firstNotNullOfOrNull { tagsById[it]?.name }
                if (label != null) reasons.add("共同标签 #$label")
            } else {
                val ancestorShared = sourceAncestors.intersect(candidateAncestors)
                if (ancestorShared.isNotEmpty()) {
                    score += 2.0
                    val label = ancestorShared.firstNotNullOfOrNull { tagsById[it]?.name }
                    if (label != null) reasons.add("同系列标签 #$label")
                }
            }

            if (sourceNotebooks.intersect(candidateNotebooks).isNotEmpty()) {
                score += 1.5
                reasons.add("同一笔记本")
            }

            val candidateNorm = vectorNorm(candidateTokens, ::idfOf)
            val lexical = idfCosineSimilarity(sourceTokens, sourceNorm, candidateTokens, candidateNorm, ::idfOf)
            if (lexical >= 0.08) {
                score += lexical * 6.5
                reasons.add("主题内容相近")
            }

            val candInstant = runCatching { Instant.parse(candidate.updatedAt) }.getOrNull()
            if (candInstant != null) {
                val hoursApart = kotlin.math.abs(Duration.between(sourceInstant, candInstant).toHours())
                if (hoursApart <= 72L && score > 0.0) {
                    score += 0.8
                    reasons.add("相近时间记录")
                }
            }

            if (score >= 1.4 && reasons.isNotEmpty()) {
                val bestChunk = candidateChunks.firstOrNull()
                val preview = bestChunk?.excerpt
                    ?: safeUtf16Slice(candidate.searchText.ifBlank { candidate.title.orEmpty() }, 0, 160)
                matches.add(
                    RelatedNoteMatch(
                        note = candidate,
                        noteTitle = displayNoteTitle(candidate),
                        previewExcerpt = preview,
                        targetBlockId = bestChunk?.let { parseFirstBlockId(it.blockIdsJson) },
                        reasons = reasons.take(3),
                        score = score,
                    ),
                )
            }
        }
        return matches
            .sortedWith(compareByDescending<RelatedNoteMatch> { it.score }.thenByDescending { it.note.updatedAt })
            .take(limit)
    }

    fun findBestEdgeEchoCandidate(
        draftText: String,
        currentNoteId: String?,
        allNotes: List<NoteEntity>,
        allChunks: List<NoteChunkEntity>,
        tags: List<TagEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        noteNotebookIdsByNote: Map<String, Set<String>>,
        excludedNoteIds: Set<String>,
    ): EvidenceHit? {
        val trimmed = draftText.trim()
        if (trimmed.length < 4) return null
        val scope = NoteScope(archived = false, aiOnly = true)
        val selfExcluded = if (currentNoteId != null) excludedNoteIds + currentNoteId else excludedNoteIds
        val res = searchEvidence(
            query = trimmed,
            scope = scope,
            allNotes = allNotes,
            allChunks = allChunks,
            tags = tags,
            noteTagIdsByNote = noteTagIdsByNote,
            noteNotebookIdsByNote = noteNotebookIdsByNote,
            excludedNoteIds = selfExcluded,
            limit = 3,
        )
        return res.hits.firstOrNull { it.score >= 2.2 }
    }

    fun buildEvidenceNoteMarkdown(
        query: String,
        items: List<EvidenceBasketItem>,
    ): String {
        val sb = StringBuilder()
        sb.append("找一找整理：").append(query.trim().ifEmpty { "证据摘要" })
        items.forEachIndexed { idx, item ->
            sb.append("\n\n").append(idx + 1).append(". ").append(item.noteTitle)
            if (item.reasons.isNotEmpty()) {
                sb.append("（").append(item.reasons.joinToString(" · ")).append("）")
            }
            sb.append("\n").append(item.excerpt)
        }
        return sb.toString()
    }

    fun displayNoteTitle(note: NoteEntity): String {
        val t = note.title?.trim()
        if (!t.isNullOrEmpty()) return t
        val firstLine = note.searchText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return if (!firstLine.isNullOrEmpty()) safeUtf16Slice(firstLine, 0, 48) else "无标题笔记"
    }

    /** Returns body text with redundant single-line title duplication stripped so cards stay clean. */
    fun bodyExcludingTitle(
        note: NoteEntity,
        rawBody: String = note.searchText,
        title: String = displayNoteTitle(note),
    ): String {
        val normalizedBody = rawBody.trim()
        val normalizedTitle = title.trim()
        if (normalizedBody.isEmpty() || normalizedBody == normalizedTitle) return ""
        val baseTitle = normalizedTitle.removeSuffix("...").removeSuffix("…").trimEnd()
        if (baseTitle.isEmpty()) return normalizedBody
        if (note.title.isNullOrBlank()) {
            val lines = normalizedBody.lines()
            val firstIdx = lines.indexOfFirst { it.isNotBlank() }
            if (firstIdx >= 0) {
                val firstLine = lines[firstIdx].trim()
                val remainder = lines.drop(firstIdx + 1).joinToString("\n").trim()
                val prefixMatched = firstLine == baseTitle ||
                    (baseTitle.length >= 24 && firstLine.startsWith(baseTitle))
                if (prefixMatched && (remainder.isNotEmpty() || firstLine.length - baseTitle.length <= 24)) {
                    return remainder
                }
            }
        } else if (normalizedBody.startsWith(baseTitle)) {
            val tail = normalizedBody.substring(baseTitle.length)
            if (tail.isBlank()) return ""
            if (tail.first() == '\n') return tail.trim()
            if (baseTitle.length >= 20) {
                val nl = tail.indexOf('\n')
                return if (nl >= 0) tail.substring(nl + 1).trim() else ""
            }
        }
        return normalizedBody
    }

    fun contentDigest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun excerptContainsExact(excerpt: String, title: String, rawLower: String): Boolean {
        if (rawLower.length < 2) return false
        return excerpt.lowercase(Locale.ROOT).contains(rawLower) ||
            title.lowercase(Locale.ROOT).contains(rawLower)
    }

    private fun tokensForNote(note: NoteEntity, chunks: List<NoteChunkEntity>?): Set<String> {
        val out = HashSet<String>()
        if (!chunks.isNullOrEmpty()) {
            for (c in chunks) {
                for (tok in c.tokenPayload.split(' ')) {
                    if (tok.length >= 2) out.add(tok)
                }
            }
        }
        if (out.isEmpty()) {
            for (tok in tokenizeForIndex("${note.title.orEmpty()} ${note.searchText}")) {
                if (tok.length >= 2) out.add(tok)
            }
        }
        return out
    }

    private fun vectorNorm(tokens: Set<String>, idfOf: (String) -> Double): Double {
        if (tokens.isEmpty()) return 0.0
        var sumSq = 0.0
        for (tok in tokens) {
            val w = idfOf(tok)
            sumSq += w * w
        }
        return sqrt(sumSq)
    }

    private fun idfCosineSimilarity(
        a: Set<String>,
        aNorm: Double,
        b: Set<String>,
        bNorm: Double,
        idfOf: (String) -> Double,
    ): Double {
        if (a.isEmpty() || b.isEmpty() || aNorm <= 0.0 || bNorm <= 0.0) return 0.0
        val (smaller, larger) = if (a.size <= b.size) a to b else b to a
        var dot = 0.0
        for (tok in smaller) {
            if (tok in larger) {
                val w = idfOf(tok)
                dot += w * w
            }
        }
        return if (dot > 0.0) dot / (aNorm * bNorm) else 0.0
    }

    private fun ancestorTagIds(tagIds: Set<String>, tagsById: Map<String, TagEntity>): Set<String> {
        val result = HashSet<String>()
        for (id in tagIds) {
            var cur = tagsById[id]
            while (cur != null) {
                result.add(cur.id)
                cur = cur.parentId.takeIf { it.isNotEmpty() }?.let { tagsById[it] }
            }
        }
        return result
    }

    private fun encodeStringArrayJson(items: List<String>): String = buildString {
        append('[')
        items.forEachIndexed { idx, value ->
            if (idx > 0) append(',')
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
        append(']')
    }

    private fun parseFirstBlockId(json: String): String? {
        val openQuote = json.indexOf('"')
        if (openQuote < 0) return null
        val sb = StringBuilder()
        var i = openQuote + 1
        while (i < json.length) {
            val ch = json[i]
            if (ch == '\\' && i + 1 < json.length) {
                sb.append(json[i + 1])
                i += 2
                continue
            }
            if (ch == '"') {
                return sb.toString().takeIf { it.isNotEmpty() }
            }
            sb.append(ch)
            i += 1
        }
        return null
    }
}
