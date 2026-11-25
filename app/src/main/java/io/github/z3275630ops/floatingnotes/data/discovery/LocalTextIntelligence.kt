package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

internal data class TextRankSummary(
    val sentences: List<String>,
    val joined: String,
    val isSummarized: Boolean,
)

internal data class DuplicateNoteGroup(
    val groupId: String,
    val primaryNote: NoteEntity,
    val duplicateNotes: List<NoteEntity>,
    val similarityPercent: Int,
    val reason: String,
    val previewText: String,
)

internal data class MergedDuplicateContent(
    val blocks: List<NoteBlock>,
    val previewLines: List<String>,
    val preservedCheckboxCount: Int,
    val preservedAttachmentCount: Int,
    val preservedReferenceCount: Int,
    val hadRemindersInSources: Boolean,
)

/**
 * Pure on-device text intelligence:
 * 1. Extractive TextRank sentence summarizer (zero hallucination, preserves reading order).
 * 2. 64-bit FNV-1a SimHash + IDF-weighted Jaccard + containment duplicate note clustering.
 * 3. Safe duplicate paragraph/checkbox merger.
 */
internal object LocalTextIntelligence {

    const val MAX_FEATURE_CACHE_ENTRIES = 12_000

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME_64 = 1099511628211L              // 0x100000001b3

    private val featureCacheLock = Any()
    private val featureCache = object : LinkedHashMap<FeatureCacheKey, CachedNoteFeature>(
        256,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<FeatureCacheKey, CachedNoteFeature>?,
        ): Boolean = size > MAX_FEATURE_CACHE_ENTRIES
    }

    internal fun clearFeatureCacheForTesting() {
        synchronized(featureCacheLock) {
            featureCache.clear()
        }
    }

    internal fun featureCacheSizeForTesting(): Int = synchronized(featureCacheLock) {
        featureCache.size
    }

    private val LINE_SPLIT_REGEX = Regex("\\r?\\n+")
    private val URL_LINE_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val CJK_TERMINATORS = setOf('。', '！', '？', '!', '?', ';', '；')
    private val ABBREVIATIONS = setOf("e.g", "i.e", "dr", "mr", "mrs", "ms", "vs", "etc", "st", "no", "u.s", "a.m", "p.m")

    private val IGNORED_SOURCE_TYPES = setOf(
        "agent_local",
        "walk_collision",
        "walk_collision_ai",
        "merge_cleanup",
    )

    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val rawLines = text.split(LINE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return emptyList()

        val nonUrlLines = rawLines.filterNot { URL_LINE_REGEX.matches(it) }
        val effectiveLines = if (nonUrlLines.isNotEmpty()) nonUrlLines else rawLines

        val sentences = ArrayList<String>()
        for (line in effectiveLines) {
            if (URL_LINE_REGEX.matches(line)) {
                sentences.add(line)
                continue
            }
            splitLineIntoSentences(line, sentences)
        }
        return sentences
    }

    private fun splitLineIntoSentences(line: String, out: MutableList<String>) {
        var start = 0
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            val isSentenceBreak = when {
                ch in CJK_TERMINATORS -> true
                ch == '.' -> isAsciiSentencePeriod(line, i)
                else -> false
            }
            if (isSentenceBreak) {
                var end = i + 1
                while (end < line.length && line[end] in CJK_TERMINATORS) {
                    end++
                }
                val chunk = line.substring(start, end).trim()
                if (chunk.isNotEmpty()) out.add(chunk)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < line.length) {
            val tail = line.substring(start).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
    }

    private fun isAsciiSentencePeriod(line: String, dotIndex: Int): Boolean {
        val atBoundary = dotIndex == line.length - 1 || line[dotIndex + 1].isWhitespace()
        if (!atBoundary || dotIndex == 0) return false
        val prev = line[dotIndex - 1]
        if (prev.isDigit()) return false
        var wordStart = dotIndex - 1
        while (wordStart >= 0 && (line[wordStart].isLetterOrDigit() || line[wordStart] == '.')) {
            wordStart--
        }
        val prevToken = line.substring(wordStart + 1, dotIndex).lowercase(Locale.ROOT)
        if (prevToken in ABBREVIATIONS) return false
        if (prevToken.length == 1 && prev.isUpperCase()) return false
        return true
    }

    fun extractKeySentences(
        text: String,
        maxSentences: Int = 2,
        maxChars: Int = 180,
    ): TextRankSummary {
        val targetCount = maxSentences.coerceAtLeast(1)
        val charLimit = maxChars.coerceAtLeast(1)
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) {
            return TextRankSummary(sentences = emptyList(), joined = "", isSummarized = false)
        }
        val trimmedText = text.trim()
        if (sentences.size <= targetCount && trimmedText.length <= charLimit) {
            val budgeted = enforceSentenceCharBudget(sentences, charLimit)
            val joined = safeUtf16Slice(budgeted.joinToString(" "), 0, charLimit)
            return TextRankSummary(sentences = budgeted, joined = joined, isSummarized = false)
        }

        val candidates = if (sentences.size > 80) sentences.take(80) else sentences
        val n = candidates.size
        val tokenSets = candidates.map { LocalRetrievalEngine.tokenizeForIndex(it).toHashSet() }

        val df = HashMap<String, Int>()
        for (set in tokenSets) {
            for (tok in set) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val idf = HashMap<String, Double>(df.size)
        for ((tok, count) in df) {
            idf[tok] = ln(1.0 + (n.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        val weights = Array(n) { DoubleArray(n) }
        val outSum = DoubleArray(n)
        for (i in 0 until n) {
            val si = tokenSets[i]
            if (si.isEmpty()) continue
            val logI = ln(1.0 + si.size.toDouble())
            for (j in i + 1 until n) {
                val sj = tokenSets[j]
                if (sj.isEmpty()) continue
                val (smaller, larger) = if (si.size <= sj.size) si to sj else sj to si
                var overlapIdf = 0.0
                for (tok in smaller) {
                    if (tok in larger) {
                        overlapIdf += idf[tok] ?: 0.0
                    }
                }
                if (overlapIdf > 0.0) {
                    val denom = logI + ln(1.0 + sj.size.toDouble())
                    val sim = if (denom > 0.0) overlapIdf / denom else 0.0
                    weights[i][j] = sim
                    weights[j][i] = sim
                    outSum[i] += sim
                    outSum[j] += sim
                }
            }
        }

        val damping = 0.85
        var scores = DoubleArray(n) { 1.0 }
        repeat(18) {
            var danglingSum = 0.0
            for (j in 0 until n) {
                if (outSum[j] <= 0.0) danglingSum += scores[j]
            }
            val danglingShare = damping * danglingSum / n.toDouble()
            val next = DoubleArray(n)
            for (i in 0 until n) {
                var incoming = 0.0
                for (j in 0 until n) {
                    if (i != j && outSum[j] > 0.0 && weights[j][i] > 0.0) {
                        incoming += (weights[j][i] / outSum[j]) * scores[j]
                    }
                }
                next[i] = (1.0 - damping) + danglingShare + damping * incoming
            }
            scores = next
        }

        scores[0] += 0.08
        if (n > 1) {
            scores[n - 1] += 0.04
        }

        val chosenIndices = (0 until n)
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .take(targetCount)
            .sorted()

        val picked = enforceSentenceCharBudget(chosenIndices.map { candidates[it] }, charLimit)
        val joined = safeUtf16Slice(picked.joinToString(" "), 0, charLimit)
        return TextRankSummary(sentences = picked, joined = joined, isSummarized = true)
    }

    private fun enforceSentenceCharBudget(sentences: List<String>, maxChars: Int): List<String> {
        var remaining = maxChars
        val out = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            if (remaining <= 0) break
            val sepCost = if (out.isEmpty()) 0 else 1
            val avail = remaining - sepCost
            if (avail <= 0) break
            val clipped = if (sentence.length <= avail) sentence else safeUtf16Slice(sentence, 0, avail)
            if (clipped.isNotEmpty()) {
                out.add(clipped)
                remaining -= sepCost + clipped.length
            }
        }
        return out
    }

    fun computeSimHash64(
        tokens: Collection<String>,
        tokenWeights: Map<String, Double> = emptyMap(),
    ): Long {
        if (tokens.isEmpty()) return 0L
        val bits = DoubleArray(64)
        for (token in tokens) {
            if (token.isEmpty()) continue
            val weight = tokenWeights[token] ?: 1.0
            if (weight == 0.0) continue
            val hash = fnv1a64(token)
            for (bit in 0 until 64) {
                if (((hash ushr bit) and 1L) == 1L) {
                    bits[bit] += weight
                } else {
                    bits[bit] -= weight
                }
            }
        }
        var fingerprint = 0L
        for (bit in 0 until 64) {
            if (bits[bit] > 0.0) {
                fingerprint = fingerprint or (1L shl bit)
            }
        }
        return fingerprint
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun findDuplicateGroups(
        notes: List<NoteEntity>,
        allChunks: List<NoteChunkEntity> = emptyList(),
        minSimilarityPercent: Int = 65,
        maxGroups: Int = 10,
        checkCancelled: () -> Unit = {},
    ): List<DuplicateNoteGroup> {
        val chunksByNote = if (allChunks.isEmpty()) emptyMap() else allChunks.groupBy { it.noteId }
        val eligible = extractEligibleNoteFeatures(
            notes = notes,
            chunksByNote = chunksByNote,
            checkCancelled = checkCancelled,
        )

        if (eligible.size < 2) return emptyList()

        val docCount = eligible.size
        val df = HashMap<String, Int>(docCount * 4)
        for (item in eligible) {
            for (tok in item.tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val idf = HashMap<String, Double>(df.size)
        val tokenHashes = HashMap<String, Long>(df.size)
        for ((tok, count) in df) {
            idf[tok] = ln(1.0 + (docCount.toDouble() + 0.5) / (count.toDouble() + 0.5))
            if (tok.isNotEmpty()) {
                tokenHashes[tok] = fnv1a64(tok)
            }
        }

        val simHashes = LongArray(docCount)
        val totalWeights = DoubleArray(docCount)
        val bits = DoubleArray(64)
        for (i in 0 until docCount) {
            if ((i and 63) == 0) checkCancelled()
            val toks = eligible[i].tokens
            bits.fill(0.0)
            var sum = 0.0
            for (tok in toks) {
                if (tok.isEmpty()) continue
                val weight = idf[tok] ?: 1.0
                sum += weight
                if (weight == 0.0) continue
                val hash = tokenHashes[tok] ?: fnv1a64(tok)
                for (bit in 0 until 64) {
                    if (((hash ushr bit) and 1L) == 1L) {
                        bits[bit] += weight
                    } else {
                        bits[bit] -= weight
                    }
                }
            }
            var fingerprint = 0L
            for (bit in 0 until 64) {
                if (bits[bit] > 0.0) {
                    fingerprint = fingerprint or (1L shl bit)
                }
            }
            simHashes[i] = fingerprint
            totalWeights[i] = sum
        }

        val adj = Array(docCount) { ArrayList<PairEdge>() }
        var pairChecks = 0

        fun evaluateAndRecord(i: Int, j: Int) {
            if ((pairChecks++ and 63) == 0) checkCancelled()
            val match = evaluateDuplicatePair(
                left = eligible[i],
                right = eligible[j],
                leftSimHash = simHashes[i],
                rightSimHash = simHashes[j],
                leftWeightSum = totalWeights[i],
                rightWeightSum = totalWeights[j],
                idf = idf,
            ) ?: return
            if (match.similarityPercent >= minSimilarityPercent) {
                val edge = PairEdge(i, j, match.similarityPercent, match.reason)
                adj[i].add(edge)
                adj[j].add(PairEdge(j, i, match.similarityPercent, match.reason))
            }
        }

        if (docCount <= 200) {
            for (i in 0 until docCount) {
                for (j in i + 1 until docCount) {
                    evaluateAndRecord(i, j)
                }
            }
        } else {
            val buckets = HashMap<Long, MutableList<Int>>(docCount * 4)
            val seenPairs = HashSet<Long>()
            for (i in 0 until docCount) {
                if ((i and 63) == 0) checkCancelled()
                val h = simHashes[i]
                for (band in 0 until 4) {
                    val bandKey = (band.toLong() shl 48) or ((h ushr (band * 16)) and 0xFFFFL)
                    buckets.getOrPut(bandKey) { ArrayList(2) }.add(i)
                }
                val topTokens = selectTopKTokens(eligible[i].tokens, idf, 8, df)
                for (tok in topTokens) {
                    val tokHash = tokenHashes[tok] ?: fnv1a64(tok)
                    val tokKey = (1L shl 56) or (tokHash and 0x00FF_FFFF_FFFF_FFFFL)
                    buckets.getOrPut(tokKey) { ArrayList(2) }.add(i)
                }
            }
            for (members in buckets.values) {
                if (members.size < 2 || members.size > 256) continue
                for (aIdx in members.indices) {
                    val u = members[aIdx]
                    for (bIdx in aIdx + 1 until members.size) {
                        val v = members[bIdx]
                        if (u == v) continue
                        val lo = minOf(u, v)
                        val hi = maxOf(u, v)
                        val pairKey = (lo.toLong() shl 32) or (hi.toLong() and 0xFFFF_FFFFL)
                        if (seenPairs.add(pairKey)) {
                            evaluateAndRecord(lo, hi)
                        }
                    }
                }
            }
        }

        // Greedy star clustering: every duplicate directly clears minSimilarityPercent against its primary seed.
        val candidateSeeds = (0 until docCount)
            .filter { adj[it].isNotEmpty() }
            .sortedWith(
                compareByDescending<Int> { adj[it].size }
                    .thenByDescending { eligible[it].normalized.length }
                    .thenByDescending { eligible[it].note.updatedAt }
                    .thenBy { eligible[it].note.id },
            )
        val assigned = BooleanArray(docCount)
        val groups = ArrayList<DuplicateNoteGroup>()

        for (seedIdx in candidateSeeds) {
            checkCancelled()
            if (assigned[seedIdx]) continue
            val directEdges = adj[seedIdx].filter { !assigned[it.rightIdx] }
            if (directEdges.isEmpty()) continue

            assigned[seedIdx] = true
            val sortedEdges = directEdges.sortedWith(
                compareByDescending<PairEdge> { it.similarityPercent }
                    .thenByDescending { reasonPriority(it.reason) }
                    .thenByDescending { eligible[it.rightIdx].note.updatedAt }
                    .thenBy { eligible[it.rightIdx].note.id },
            )
            for (e in sortedEdges) {
                assigned[e.rightIdx] = true
            }

            val primaryNote = eligible[seedIdx].note
            val duplicateNotes = sortedEdges.map { eligible[it.rightIdx].note }
            val sortedIds = (listOf(primaryNote.id) + duplicateNotes.map { it.id }).sorted()
            val groupId = LocalRetrievalEngine.contentDigest(sortedIds.joinToString("|"))

            val bestEdge = sortedEdges.first()
            val rawPreview = LocalRetrievalEngine.bodyExcludingTitle(primaryNote)
                .ifBlank { primaryNote.searchText.ifBlank { primaryNote.title.orEmpty() } }
            val previewText = safeUtf16Slice(rawPreview.trim(), 0, 140)

            groups.add(
                DuplicateNoteGroup(
                    groupId = groupId,
                    primaryNote = primaryNote,
                    duplicateNotes = duplicateNotes,
                    similarityPercent = bestEdge.similarityPercent,
                    reason = bestEdge.reason,
                    previewText = previewText,
                ),
            )
        }

        return groups
            .sortedWith(
                compareByDescending<DuplicateNoteGroup> { it.similarityPercent }
                    .thenByDescending { it.duplicateNotes.size }
                    .thenByDescending { it.primaryNote.updatedAt }
                    .thenBy { it.groupId },
            )
            .take(maxGroups.coerceAtLeast(1))
    }

    fun buildMergedParagraphs(
        primary: NoteEntity,
        duplicates: List<NoteEntity>,
    ): List<String> {
        val orderedNotes = listOf(primary) + duplicates
        val seenKeys = HashSet<String>()
        val merged = ArrayList<String>()
        for (note in orderedNotes) {
            val lines = extractNoteParagraphLines(note)
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                val key = normalizeForComparison(trimmed).ifEmpty {
                    trimmed.lowercase(Locale.ROOT)
                }
                if (seenKeys.add(key)) {
                    merged.add(trimmed)
                }
            }
        }
        return merged
    }

    /** Preserves checkboxes (checked state + dueDate), media attachments, and non-trashed references when merging duplicates. */
    fun buildMergedContent(
        primary: NoteEntity,
        duplicates: List<NoteEntity>,
        moveDuplicatesToTrash: Boolean = false,
    ): MergedDuplicateContent {
        val orderedNotes = listOf(primary) + duplicates
        val sourceNoteIds = orderedNotes.mapTo(HashSet()) { it.id }
        val blocks = ArrayList<NoteBlock>()
        val seenParagraphKeys = HashSet<String>()
        val seenCheckboxIndex = HashMap<String, Int>()
        val seenAttachmentIds = HashSet<String>()
        val seenRefKeys = HashSet<String>()
        val blockRefLabels = HashMap<String, String>()

        var preservedCheckboxes = 0
        var preservedAttachments = 0
        var preservedReferences = 0
        var hadReminders = false

        for (note in orderedNotes) {
            val labelsByBlockId = extractReferenceLabels(note.documentJson)
            val decodedBlocks = runCatching { NoteDocument.decode(note.documentJson).blocks }.getOrNull()
            if (decodedBlocks == null) {
                for (rawLine in extractNoteParagraphLines(note)) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) continue
                    val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                    if (seenParagraphKeys.add(key)) {
                        blocks.add(NoteBlock.paragraph(trimmed))
                    }
                }
                continue
            }

            for (raw in decodedBlocks) {
                when (raw.type) {
                    "paragraph" -> {
                        for (rawLine in raw.text.lines()) {
                            val trimmed = rawLine.trim()
                            if (trimmed.isEmpty()) continue
                            val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                            if (seenParagraphKeys.add(key)) {
                                blocks.add(NoteBlock.paragraph(trimmed, indent = raw.indent))
                            }
                        }
                    }
                    "heading" -> {
                        val trimmed = raw.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                            if (seenParagraphKeys.add(key)) {
                                blocks.add(NoteBlock.heading(level = raw.level, text = trimmed, indent = raw.indent))
                            }
                        }
                    }
                    "bullet", "numbered", "quote" -> {
                        for (rawLine in raw.text.lines()) {
                            val trimmed = rawLine.trim()
                            if (trimmed.isEmpty()) continue
                            val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                            if (seenParagraphKeys.add(key)) {
                                val preserved = when (raw.type) {
                                    "bullet" -> NoteBlock.bullet(trimmed, indent = raw.indent)
                                    "numbered" -> NoteBlock.numbered(trimmed, indent = raw.indent)
                                    else -> NoteBlock.quote(trimmed, indent = raw.indent)
                                }
                                blocks.add(preserved)
                            }
                        }
                    }
                    "callout" -> {
                        val trimmed = raw.text.trim()
                        if (trimmed.isNotEmpty()) {
                            val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                            if (seenParagraphKeys.add(key)) {
                                blocks.add(NoteBlock.callout(text = trimmed, tone = raw.tone, indent = raw.indent))
                            }
                        }
                    }
                    "toggle" -> {
                        val combined = raw.searchableText.trim()
                        if (combined.isNotEmpty()) {
                            val key = normalizeForComparison(combined).ifEmpty { combined.lowercase(Locale.ROOT) }
                            if (seenParagraphKeys.add(key)) {
                                blocks.add(
                                    NoteBlock.toggle(
                                        title = raw.title.trim(),
                                        text = raw.text.trim(),
                                        collapsed = raw.collapsed,
                                        indent = raw.indent,
                                    ),
                                )
                            }
                        }
                    }
                    "divider" -> {
                        // Decorative dividers carry no text content for duplicate merging.
                    }
                    "checkbox" -> {
                        val trimmed = raw.text.trim().ifEmpty { "未命名待办" }
                        val key = normalizeForComparison(trimmed).ifEmpty { trimmed.lowercase(Locale.ROOT) }
                        if (raw.reminderId != null) hadReminders = true
                        val existingIdx = seenCheckboxIndex[key]
                        if (existingIdx == null) {
                            val fresh = NoteBlock.checkbox(
                                text = trimmed,
                                checked = raw.checked,
                                dueDate = raw.dueDate,
                                reminderId = null,
                                occurrenceId = null,
                            )
                            seenCheckboxIndex[key] = blocks.size
                            blocks.add(fresh)
                            preservedCheckboxes++
                        } else {
                            val existing = blocks[existingIdx]
                            val mergedChecked = existing.checked || raw.checked
                            val mergedDue = existing.dueDate ?: raw.dueDate
                            var updated = if (existing.checked != mergedChecked) existing.withChecked(mergedChecked) else existing
                            if (updated.dueDate != mergedDue) {
                                updated = updated.withTaskSchedule(dueDate = mergedDue, reminderId = null, occurrenceId = null)
                            }
                            blocks[existingIdx] = updated
                        }
                    }
                    "image", "audio", "video" -> {
                        val attId = raw.attachmentId ?: continue
                        if (seenAttachmentIds.add(attId)) {
                            blocks.add(NoteBlock.attachment(raw.type, attId))
                            preservedAttachments++
                        }
                    }
                    "reference" -> {
                        val targetId = raw.referenceTarget ?: continue
                        if (moveDuplicatesToTrash && targetId in sourceNoteIds) continue
                        val refKey = "$targetId|${raw.referenceTargetBlockId.orEmpty()}|${raw.referenceStart ?: -1}|${raw.referenceEnd ?: -1}"
                        if (seenRefKeys.add(refKey)) {
                            val label = labelsByBlockId[raw.id]?.takeIf { it.isNotBlank() } ?: "关联笔记"
                            val freshRef = NoteBlock.reference(
                                noteId = targetId,
                                revision = raw.referenceRevision ?: 1L,
                                label = label,
                                targetBlockId = raw.referenceTargetBlockId,
                                start = raw.referenceStart,
                                end = raw.referenceEnd,
                            )
                            blockRefLabels[freshRef.id] = label
                            blocks.add(freshRef)
                            preservedReferences++
                        }
                    }
                }
            }
        }

        val previewLines = blocks.map { block ->
            when (block.type) {
                "heading" -> "${"#".repeat(block.level)} ${block.text}"
                "bullet" -> "• ${block.text}"
                "numbered" -> "1. ${block.text}"
                "quote" -> "> ${block.text}"
                "callout" -> "[提示] ${block.text}"
                "toggle" -> if (block.text.isNotBlank()) "▸ ${block.title}\n${block.text}" else "▸ ${block.title}"
                "divider" -> "---"
                "checkbox" -> buildString {
                    append(if (block.checked) "☑ " else "☐ ")
                    append(block.text)
                    block.dueDate?.let { append(" · $it") }
                }
                "image" -> "[图片附件]"
                "audio" -> "[语音附件]"
                "video" -> "[视频附件]"
                "reference" -> "@ ${blockRefLabels[block.id] ?: "关联笔记"}"
                else -> block.text
            }
        }

        return MergedDuplicateContent(
            blocks = blocks,
            previewLines = previewLines,
            preservedCheckboxCount = preservedCheckboxes,
            preservedAttachmentCount = preservedAttachments,
            preservedReferenceCount = preservedReferences,
            hadRemindersInSources = hadReminders,
        )
    }

    private fun extractReferenceLabels(documentJson: String): Map<String, String> = runCatching {
        val root = MiniJson.parseObject(documentJson)
        val rawBlocks = root["blocks"] as? List<*> ?: return@runCatching emptyMap()
        val labels = HashMap<String, String>()
        for (entry in rawBlocks) {
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?> ?: continue
            if (obj["type"]?.toString() != "reference") continue
            val id = obj["id"]?.toString() ?: continue
            val label = obj["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            labels[id] = label
        }
        labels
    }.getOrDefault(emptyMap())

    private val TEXT_BEARING_BLOCK_TYPES = setOf(
        "paragraph",
        "heading",
        "bullet",
        "numbered",
        "checkbox",
        "quote",
        "callout",
        "toggle",
    )

    private fun extractNoteParagraphLines(note: NoteEntity): List<String> {
        val fromDocument = runCatching {
            NoteDocument.decode(note.documentJson).blocks
                .filter { it.type in TEXT_BEARING_BLOCK_TYPES }
                .flatMap { it.searchableText.lines() }
        }.getOrNull() ?: runCatching {
            val root = MiniJson.parseObject(note.documentJson)
            if ((root["document_schema_version"] as? Number)?.toInt() != 1) return@runCatching null
            @Suppress("UNCHECKED_CAST")
            val rawBlocks = root["blocks"] as? List<*> ?: return@runCatching null
            rawBlocks
                .mapNotNull { it as? Map<String, Any?> }
                .filter { block -> block["type"]?.toString() in TEXT_BEARING_BLOCK_TYPES }
                .flatMap { block ->
                    if (block["type"]?.toString() == "toggle") {
                        listOfNotNull(
                            block["title"]?.toString(),
                            block["text"]?.toString(),
                        ).flatMap { it.lines() }
                    } else {
                        block["text"]?.toString().orEmpty().lines()
                    }
                }
        }.getOrNull()

        return fromDocument ?: note.searchText.lines()
    }

    private fun evaluateDuplicatePair(
        left: NoteFeature,
        right: NoteFeature,
        leftSimHash: Long,
        rightSimHash: Long,
        leftWeightSum: Double,
        rightWeightSum: Double,
        idf: Map<String, Double>,
    ): PairMatch? {
        if (left.normalized == right.normalized) {
            return PairMatch(similarityPercent = 100, reason = "内容完全相同")
        }

        val (smallerTokens, largerTokens) = if (left.tokens.size <= right.tokens.size) {
            left.tokens to right.tokens
        } else {
            right.tokens to left.tokens
        }
        var intersectionCount = 0
        var intersectionWeight = 0.0
        for (tok in smallerTokens) {
            if (tok in largerTokens) {
                intersectionCount += 1
                intersectionWeight += idf[tok] ?: 1.0
            }
        }
        if (intersectionCount == 0) return null

        val unionWeight = leftWeightSum + rightWeightSum - intersectionWeight
        val weightedJaccard = if (unionWeight > 0.0) intersectionWeight / unionWeight else 0.0

        val minTokens = minOf(left.tokens.size, right.tokens.size)
        val maxTokens = maxOf(left.tokens.size, right.tokens.size)
        val tokenContainment = if (minTokens > 0) intersectionCount.toDouble() / minTokens.toDouble() else 0.0

        val (shorterNorm, longerNorm) = if (left.normalized.length <= right.normalized.length) {
            left.normalized to right.normalized
        } else {
            right.normalized to left.normalized
        }
        val stringContains = shorterNorm.length >= 8 && longerNorm.contains(shorterNorm)
        val containment = if (stringContains) 1.0 else tokenContainment

        val hamming = hammingDistance(leftSimHash, rightSimHash)
        val simHashMatched = hamming <= 16 && weightedJaccard >= 0.55

        if (containment >= 0.80 && (stringContains || minTokens < maxTokens * 0.85 || !simHashMatched)) {
            val simPercent = maxOf(
                (weightedJaccard * 100.0).roundToInt(),
                (containment * 90.0).roundToInt(),
            ).coerceIn(1, 99)
            return PairMatch(similarityPercent = simPercent, reason = "一条几乎包含另一条")
        }

        if (simHashMatched) {
            val hammingSim = 1.0 - hamming.toDouble() / 64.0
            val simPercent = ((0.5 * weightedJaccard + 0.5 * hammingSim) * 100.0)
                .roundToInt()
                .coerceIn(1, 99)
            return PairMatch(similarityPercent = simPercent, reason = "内容高度相似")
        }

        if (containment >= 0.80) {
            val simPercent = maxOf(
                (weightedJaccard * 100.0).roundToInt(),
                (containment * 90.0).roundToInt(),
            ).coerceIn(1, 99)
            return PairMatch(similarityPercent = simPercent, reason = "一条几乎包含另一条")
        }

        return null
    }

    private fun reasonPriority(reason: String): Int = when (reason) {
        "内容完全相同" -> 3
        "一条几乎包含另一条" -> 2
        else -> 1
    }

    private fun noteCombinedText(note: NoteEntity): String {
        val title = note.title?.trim().orEmpty()
        val body = LocalRetrievalEngine.bodyExcludingTitle(note).trim()
        return when {
            title.isNotEmpty() && body.isNotEmpty() -> "$title\n$body"
            title.isNotEmpty() -> title
            else -> body.ifEmpty { note.searchText.trim() }
        }
    }

    private fun normalizeForComparison(text: String): String = buildString(text.length) {
        for (ch in text.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) append(ch)
        }
    }

    internal fun selectTopKTokens(
        tokens: Collection<String>,
        idf: Map<String, Double>,
        k: Int = 8,
        df: Map<String, Int>? = null,
    ): List<String> {
        if (k <= 0 || tokens.isEmpty()) return emptyList()
        if (df == null && tokens.size <= k) {
            return tokens.sortedByDescending { idf[it] ?: 0.0 }
        }
        val heapTokens = arrayOfNulls<String>(k)
        val heapWeights = DoubleArray(k)
        val heapIndices = IntArray(k)
        var size = 0

        fun isWorse(a: Int, b: Int): Boolean {
            val wa = heapWeights[a]
            val wb = heapWeights[b]
            return wa < wb || (wa == wb && heapIndices[a] > heapIndices[b])
        }

        fun swap(a: Int, b: Int) {
            val tmpTok = heapTokens[a]
            heapTokens[a] = heapTokens[b]
            heapTokens[b] = tmpTok
            val tmpW = heapWeights[a]
            heapWeights[a] = heapWeights[b]
            heapWeights[b] = tmpW
            val tmpIdx = heapIndices[a]
            heapIndices[a] = heapIndices[b]
            heapIndices[b] = tmpIdx
        }

        var idx = 0
        for (tok in tokens) {
            if (df != null) {
                val freq = df[tok] ?: 0
                if (freq < 2 || freq > 256) {
                    idx++
                    continue
                }
            }
            val weight = idf[tok] ?: 0.0
            if (size < k) {
                var pos = size
                heapTokens[pos] = tok
                heapWeights[pos] = weight
                heapIndices[pos] = idx
                size++
                while (pos > 0) {
                    val parent = (pos - 1) ushr 1
                    if (isWorse(pos, parent)) {
                        swap(pos, parent)
                        pos = parent
                    } else {
                        break
                    }
                }
            } else if (weight > heapWeights[0]) {
                heapTokens[0] = tok
                heapWeights[0] = weight
                heapIndices[0] = idx
                var pos = 0
                while (true) {
                    val left = (pos shl 1) + 1
                    if (left >= size) break
                    val right = left + 1
                    val worstChild = if (right < size && isWorse(right, left)) right else left
                    if (isWorse(worstChild, pos)) {
                        swap(pos, worstChild)
                        pos = worstChild
                    } else {
                        break
                    }
                }
            }
            idx++
        }

        for (i in 1 until size) {
            val curTok = heapTokens[i]
            val curW = heapWeights[i]
            val curIdx = heapIndices[i]
            var j = i - 1
            while (j >= 0 && (heapWeights[j] < curW || (heapWeights[j] == curW && heapIndices[j] > curIdx))) {
                heapTokens[j + 1] = heapTokens[j]
                heapWeights[j + 1] = heapWeights[j]
                heapIndices[j + 1] = heapIndices[j]
                j--
            }
            heapTokens[j + 1] = curTok
            heapWeights[j + 1] = curW
            heapIndices[j + 1] = curIdx
        }

        val out = ArrayList<String>(size)
        for (i in 0 until size) {
            out.add(heapTokens[i]!!)
        }
        return out
    }

    private fun extractEligibleNoteFeatures(
        notes: List<NoteEntity>,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
        checkCancelled: () -> Unit,
    ): List<NoteFeature> {
        val candidates = ArrayList<FeatureLookupCandidate>(notes.size)
        for (note in notes) {
            checkCancelled()
            if (note.deletedAt != null || note.archivedAt != null || note.aiPolicy == "exclude") continue
            if (note.sourceType in IGNORED_SOURCE_TYPES) continue
            val freshChunks = chunksByNote[note.id]?.takeIf { list ->
                list.isNotEmpty() && list.all {
                    it.noteRevision == note.revision && it.contentHash == note.contentHash
                }
            }
            val key = FeatureCacheKey(
                noteId = note.id,
                revision = note.revision,
                contentHash = note.contentHash,
                fromChunks = freshChunks != null,
            )
            candidates.add(
                FeatureLookupCandidate(
                    note = note,
                    freshChunks = freshChunks,
                    key = key,
                    titleHash = note.title?.hashCode() ?: 0,
                    searchTextHash = note.searchText.hashCode(),
                    chunkSigHash = computeChunkSigHash(freshChunks),
                ),
            )
        }
        if (candidates.isEmpty()) return emptyList()

        val resolved = arrayOfNulls<CachedNoteFeature>(candidates.size)
        val missIndices = ArrayList<Int>()
        synchronized(featureCacheLock) {
            for (i in candidates.indices) {
                val cand = candidates[i]
                val hit = featureCache[cand.key]
                if (hit != null &&
                    hit.titleHash == cand.titleHash &&
                    hit.searchTextHash == cand.searchTextHash &&
                    hit.chunkSigHash == cand.chunkSigHash
                ) {
                    resolved[i] = hit
                } else {
                    missIndices.add(i)
                }
            }
        }

        if (missIndices.isNotEmpty()) {
            val computed = ArrayList<Pair<FeatureCacheKey, CachedNoteFeature>>(missIndices.size)
            for (mIdx in missIndices.indices) {
                if ((mIdx and 63) == 0) checkCancelled()
                val candIdx = missIndices[mIdx]
                val cand = candidates[candIdx]
                val rawText = noteCombinedText(cand.note)
                val normalized = normalizeForComparison(rawText)
                val tokenSet = if (normalized.length < 8) {
                    emptySet()
                } else if (cand.freshChunks != null) {
                    cand.freshChunks
                        .flatMap { it.tokenPayload.split(' ') }
                        .filter { it.isNotEmpty() }
                        .toHashSet()
                } else {
                    LocalRetrievalEngine.tokenizeForIndex(rawText).toHashSet()
                }
                val entry = CachedNoteFeature(
                    titleHash = cand.titleHash,
                    searchTextHash = cand.searchTextHash,
                    chunkSigHash = cand.chunkSigHash,
                    normalized = normalized,
                    tokens = tokenSet,
                )
                resolved[candIdx] = entry
                computed.add(cand.key to entry)
            }
            synchronized(featureCacheLock) {
                for ((k, v) in computed) {
                    featureCache[k] = v
                }
            }
        }

        val eligible = ArrayList<NoteFeature>(candidates.size)
        for (i in candidates.indices) {
            val feat = resolved[i] ?: continue
            if (feat.normalized.length < 8 || feat.tokens.isEmpty()) continue
            eligible.add(
                NoteFeature(
                    note = candidates[i].note,
                    normalized = feat.normalized,
                    tokens = feat.tokens,
                ),
            )
        }
        return eligible
    }

    private fun computeChunkSigHash(chunks: List<NoteChunkEntity>?): Int {
        if (chunks == null) return 0
        var h = chunks.size
        for (c in chunks) {
            h = 31 * h + c.id.hashCode()
            h = 31 * h + c.tokenPayload.hashCode()
        }
        return h
    }

    private fun fnv1a64(token: String): Long {
        var hash = FNV_OFFSET_BASIS_64
        val bytes = token.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= FNV_PRIME_64
        }
        return hash
    }

    private data class FeatureCacheKey(
        val noteId: String,
        val revision: Long,
        val contentHash: String,
        val fromChunks: Boolean,
    )

    private class CachedNoteFeature(
        val titleHash: Int,
        val searchTextHash: Int,
        val chunkSigHash: Int,
        val normalized: String,
        val tokens: Set<String>,
    )

    private class FeatureLookupCandidate(
        val note: NoteEntity,
        val freshChunks: List<NoteChunkEntity>?,
        val key: FeatureCacheKey,
        val titleHash: Int,
        val searchTextHash: Int,
        val chunkSigHash: Int,
    )

    private class NoteFeature(
        val note: NoteEntity,
        val normalized: String,
        val tokens: Set<String>,
    )

    private class PairMatch(
        val similarityPercent: Int,
        val reason: String,
    )

    private class PairEdge(
        val leftIdx: Int,
        val rightIdx: Int,
        val similarityPercent: Int,
        val reason: String,
    )
}
