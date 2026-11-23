package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
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

/**
 * Pure on-device text intelligence:
 * 1. Extractive TextRank sentence summarizer (zero hallucination, preserves reading order).
 * 2. 64-bit FNV-1a SimHash + IDF-weighted Jaccard + containment duplicate note clustering.
 * 3. Safe duplicate paragraph/checkbox merger.
 */
internal object LocalTextIntelligence {

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME_64 = 1099511628211L              // 0x100000001b3

    private val LINE_SPLIT_REGEX = Regex("\\r?\\n+")
    private val URL_LINE_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    private val SENTENCE_CHUNK_REGEX = Regex("[^。！？!?;；]+[。！？!?;；]*")

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
            for (match in SENTENCE_CHUNK_REGEX.findAll(line)) {
                val sentence = match.value.trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
            }
        }
        return sentences
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
            val joined = safeUtf16Slice(sentences.joinToString(" "), 0, charLimit)
            return TextRankSummary(sentences = sentences, joined = joined, isSummarized = false)
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

        val picked = chosenIndices.map { candidates[it] }
        val joined = safeUtf16Slice(picked.joinToString(" "), 0, charLimit)
        return TextRankSummary(sentences = picked, joined = joined, isSummarized = true)
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
    ): List<DuplicateNoteGroup> {
        val chunksByNote = allChunks.groupBy { it.noteId }
        val eligible = ArrayList<NoteFeature>()
        for (note in notes) {
            if (note.deletedAt != null || note.archivedAt != null) continue
            if (note.sourceType in IGNORED_SOURCE_TYPES) continue
            val rawText = noteCombinedText(note)
            val normalized = normalizeForComparison(rawText)
            if (normalized.length < 8) continue

            val freshChunks = chunksByNote[note.id]?.takeIf { list ->
                list.isNotEmpty() && list.all {
                    it.noteRevision == note.revision && it.contentHash == note.contentHash
                }
            }
            val tokenSet = if (freshChunks != null) {
                freshChunks
                    .flatMap { it.tokenPayload.split(' ') }
                    .filter { it.isNotEmpty() }
                    .toHashSet()
            } else {
                LocalRetrievalEngine.tokenizeForIndex(rawText).toHashSet()
            }
            if (tokenSet.isEmpty()) continue

            eligible.add(
                NoteFeature(
                    note = note,
                    normalized = normalized,
                    tokens = tokenSet,
                ),
            )
        }

        if (eligible.size < 2) return emptyList()

        val docCount = eligible.size
        val df = HashMap<String, Int>()
        for (item in eligible) {
            for (tok in item.tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val idf = HashMap<String, Double>(df.size)
        for ((tok, count) in df) {
            idf[tok] = ln(1.0 + (docCount.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        val simHashes = LongArray(docCount)
        val totalWeights = DoubleArray(docCount)
        for (i in 0 until docCount) {
            val toks = eligible[i].tokens
            simHashes[i] = computeSimHash64(toks, idf)
            var sum = 0.0
            for (tok in toks) sum += idf[tok] ?: 1.0
            totalWeights[i] = sum
        }

        val uf = UnionFind(docCount)
        val edges = ArrayList<PairEdge>()
        for (i in 0 until docCount) {
            val left = eligible[i]
            for (j in i + 1 until docCount) {
                val right = eligible[j]
                val match = evaluateDuplicatePair(
                    left = left,
                    right = right,
                    leftSimHash = simHashes[i],
                    rightSimHash = simHashes[j],
                    leftWeightSum = totalWeights[i],
                    rightWeightSum = totalWeights[j],
                    idf = idf,
                ) ?: continue

                if (match.similarityPercent >= minSimilarityPercent) {
                    uf.union(i, j)
                    edges.add(PairEdge(i, j, match.similarityPercent, match.reason))
                }
            }
        }

        if (edges.isEmpty()) return emptyList()

        val components = HashMap<Int, MutableList<Int>>()
        for (i in 0 until docCount) {
            val root = uf.find(i)
            components.getOrPut(root) { ArrayList() }.add(i)
        }

        val edgesByRoot = HashMap<Int, MutableList<PairEdge>>()
        for (edge in edges) {
            val root = uf.find(edge.leftIdx)
            edgesByRoot.getOrPut(root) { ArrayList() }.add(edge)
        }

        val groups = ArrayList<DuplicateNoteGroup>()
        for ((root, memberIndices) in components) {
            if (memberIndices.size < 2) continue
            val rankedMembers = memberIndices.sortedWith(
                compareByDescending<Int> { eligible[it].normalized.length }
                    .thenByDescending { eligible[it].note.updatedAt }
                    .thenBy { eligible[it].note.id },
            )
            val primaryNote = eligible[rankedMembers.first()].note
            val duplicateNotes = rankedMembers.drop(1)
                .map { eligible[it].note }
                .sortedWith(compareByDescending<NoteEntity> { it.updatedAt }.thenBy { it.id })

            val sortedIds = memberIndices.map { eligible[it].note.id }.sorted()
            val groupId = LocalRetrievalEngine.contentDigest(sortedIds.joinToString("|"))

            val bestEdge = edgesByRoot[root].orEmpty().maxWithOrNull(
                compareBy<PairEdge> { it.similarityPercent }
                    .thenBy { reasonPriority(it.reason) },
            ) ?: continue

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

    private fun extractNoteParagraphLines(note: NoteEntity): List<String> {
        val fromDocument = runCatching {
            NoteDocument.decode(note.documentJson).blocks
                .filter { it.type == "paragraph" || it.type == "checkbox" }
                .flatMap { it.text.lines() }
        }.getOrNull() ?: runCatching {
            val root = MiniJson.parseObject(note.documentJson)
            if ((root["document_schema_version"] as? Number)?.toInt() != 1) return@runCatching null
            @Suppress("UNCHECKED_CAST")
            val rawBlocks = root["blocks"] as? List<*> ?: return@runCatching null
            rawBlocks
                .mapNotNull { it as? Map<String, Any?> }
                .filter { block ->
                    val type = block["type"]?.toString()
                    type == "paragraph" || type == "checkbox"
                }
                .flatMap { block -> block["text"]?.toString().orEmpty().lines() }
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

    private fun fnv1a64(token: String): Long {
        var hash = FNV_OFFSET_BASIS_64
        val bytes = token.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= FNV_PRIME_64
        }
        return hash
    }

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

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            when {
                rank[ra] < rank[rb] -> parent[ra] = rb
                rank[ra] > rank[rb] -> parent[rb] = ra
                else -> {
                    parent[rb] = ra
                    rank[ra] += 1
                }
            }
        }
    }
}
