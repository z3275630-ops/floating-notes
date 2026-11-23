package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

internal data class TagRecommendation(
    val tag: TagEntity,
    val score: Double,
    val reason: String,
)

/**
 * Pure on-device tag recommendation, smart dual-collision pair scoring,
 * and cross-note bridge insight computation using TF-IDF and tag co-occurrence graphs.
 */
internal object LocalTagAndGraphEngine {

    private val ALIAS_SPLIT_REGEX = Regex("[()（）/|｜,，、\\s]+")
    private val ASCII_PURE_REGEX = Regex("^[a-z0-9_.-]+$")

    fun recommendTagsForDraft(
        draftText: String,
        currentNoteId: String?,
        attachedTagIds: Set<String>,
        allNotes: List<NoteEntity>,
        allChunks: List<NoteChunkEntity>,
        tags: List<TagEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        limit: Int = 4,
    ): List<TagRecommendation> {
        if (tags.isEmpty()) return emptyList()
        val trimmed = draftText.trim()
        if (trimmed.isEmpty() && attachedTagIds.isEmpty()) return emptyList()

        val tagsById = tags.associateBy { it.id }
        val corpusNotes = allNotes.filter { it.deletedAt == null }
        val deletedIds = allNotes.filter { it.deletedAt != null }.mapTo(HashSet()) { it.id }
        val chunksByNote = allChunks.groupBy { it.noteId }

        val noteTokensById = HashMap<String, Set<String>>(corpusNotes.size)
        val df = HashMap<String, Int>()
        for (note in corpusNotes) {
            val tokens = tokensForNote(note, chunksByNote[note.id])
            noteTokensById[note.id] = tokens
            for (tok in tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val totalDocs = corpusNotes.size.coerceAtLeast(1)
        fun idfOf(tok: String): Double {
            val count = (df[tok] ?: 0).coerceAtLeast(1)
            return ln(1.0 + (totalDocs.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        // Signal (b): TF-IDF k-NN neighbor notes' tags
        val knnTagScores = HashMap<String, Double>()
        if (trimmed.isNotEmpty() && corpusNotes.isNotEmpty()) {
            val draftTokens = tokenizeMeaningful(trimmed)
            if (draftTokens.isNotEmpty()) {
                val draftVec = buildTfIdfVector(draftTokens, ::idfOf)
                val scoredNeighbors = ArrayList<Pair<NoteEntity, Double>>()
                for (note in corpusNotes) {
                    if (note.id == currentNoteId) continue
                    val noteTokens = noteTokensById[note.id].orEmpty()
                    if (noteTokens.isEmpty()) continue
                    val noteVec = buildTfIdfVector(noteTokens, ::idfOf)
                    val sim = cosineSimilarity(draftVec, noteVec)
                    if (sim >= 0.08) {
                        scoredNeighbors.add(note to sim)
                    }
                }
                val topNeighbors = scoredNeighbors
                    .sortedWith(
                        compareByDescending<Pair<NoteEntity, Double>> { it.second }
                            .thenByDescending { it.first.updatedAt }
                            .thenBy { it.first.id },
                    )
                    .take(15)
                for ((neighbor, sim) in topNeighbors) {
                    val neighborTags = noteTagIdsByNote[neighbor.id].orEmpty()
                    for (tagId in neighborTags) {
                        if (tagId !in attachedTagIds) {
                            knnTagScores[tagId] = (knnTagScores[tagId] ?: 0.0) + sim * 4.0
                        }
                    }
                }
            }
        }

        // Signal (c): Tag co-occurrence Jaccard graph with attachedTagIds
        val notesByTag = HashMap<String, MutableSet<String>>()
        for ((noteId, tagIds) in noteTagIdsByNote) {
            if (noteId in deletedIds) continue
            for (tagId in tagIds) {
                notesByTag.getOrPut(tagId) { HashSet() }.add(noteId)
            }
        }

        val draftKey = if (trimmed.isNotEmpty()) tagNameKey(trimmed) else ""
        val recommendations = ArrayList<TagRecommendation>()

        for (tag in tags) {
            if (tag.id in attachedTagIds) continue

            // Signal (a): Direct tag name / alias mention in draft
            val mentionScore = if (draftKey.isNotEmpty() && isTagMentionedInDraft(draftKey, tag)) {
                3.5
            } else {
                0.0
            }

            // Signal (b): k-NN neighbor weight
            val knnScore = knnTagScores[tag.id] ?: 0.0

            // Signal (c): Co-occurrence Jaccard with attached tags
            var coScore = 0.0
            var coReason = ""
            if (attachedTagIds.isNotEmpty()) {
                val candNotes = notesByTag[tag.id].orEmpty()
                if (candNotes.size >= 2) {
                    var bestJaccard = 0.0
                    var bestAnchorId: String? = null
                    for (attachedId in attachedTagIds) {
                        val anchorNotes = notesByTag[attachedId].orEmpty()
                        if (anchorNotes.size < 2) continue
                        val coCount = candNotes.count { it in anchorNotes }
                        if (coCount < 2) continue
                        val unionCount = candNotes.size + anchorNotes.size - coCount
                        val jaccard = if (unionCount > 0) coCount.toDouble() / unionCount.toDouble() else 0.0
                        if (jaccard >= 0.15 && jaccard > bestJaccard) {
                            bestJaccard = jaccard
                            bestAnchorId = attachedId
                        }
                    }
                    if (bestAnchorId != null) {
                        coScore = bestJaccard * 3.0
                        val anchorName = tagsById[bestAnchorId]?.name ?: bestAnchorId
                        coReason = "常与 #$anchorName 同现"
                    }
                }
            }

            val totalScore = mentionScore + knnScore + coScore
            if (totalScore < 0.45) continue

            val reason = when {
                mentionScore >= knnScore && mentionScore >= coScore && mentionScore > 0.0 -> "正文提及"
                knnScore >= coScore && knnScore > 0.0 -> "相似笔记常用"
                coScore > 0.0 -> coReason
                else -> "相似笔记常用"
            }

            recommendations.add(
                TagRecommendation(
                    tag = tag,
                    score = totalScore,
                    reason = reason,
                ),
            )
        }

        return recommendations
            .sortedWith(
                compareByDescending<TagRecommendation> { it.score }
                    .thenBy { it.tag.nameKey }
                    .thenBy { it.tag.id },
            )
            .take(limit.coerceAtLeast(1))
    }

    fun selectBestDualCollisionPair(
        eligibleNotes: List<NoteEntity>,
        chunksByNote: Map<String, List<NoteChunkEntity>>,
        noteTagIdsByNote: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
        links: List<NoteLinkEntity>,
        excludePairKeys: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): Pair<NoteEntity, NoteEntity>? {
        if (eligibleNotes.size < 2) return null

        val pool = if (eligibleNotes.size > 70) {
            eligibleNotes.shuffled(random).take(60)
        } else {
            eligibleNotes
        }

        // Compute corpus IDF across eligible notes
        val df = HashMap<String, Int>()
        val tokensByNote = HashMap<String, Set<String>>(eligibleNotes.size)
        for (note in eligibleNotes) {
            val tokens = tokensForNote(note, chunksByNote[note.id])
            tokensByNote[note.id] = tokens
            for (tok in tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val totalDocs = eligibleNotes.size.coerceAtLeast(1)
        fun idfOf(tok: String): Double {
            val count = (df[tok] ?: 0).coerceAtLeast(1)
            return ln(1.0 + (totalDocs.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        val poolVecs = Array(pool.size) { idx ->
            buildTfIdfVector(tokensByNote[pool[idx].id].orEmpty(), ::idfOf)
        }
        val poolInstants = Array(pool.size) { idx -> parseInstant(pool[idx].createdAt) }
        val poolTagSets = Array(pool.size) { idx -> noteTagIdsByNote[pool[idx].id].orEmpty() }

        val linkedPairs = links.mapTo(HashSet(links.size)) { canonicalPairKey(it.sourceId, it.targetId) }
        val coNeighbors = buildTagCoOccurrenceNeighbors(noteTagIdsByNote, excludedNoteIds = emptySet())

        val unseenCandidates = ArrayList<ScoredPair>()
        val allCandidates = ArrayList<ScoredPair>()

        for (i in pool.indices) {
            val left = pool[i]
            val leftTags = poolTagSets[i]
            val leftVec = poolVecs[i]
            val leftInstant = poolInstants[i]
            val leftOneHop = leftTags.flatMapTo(HashSet()) { coNeighbors[it].orEmpty() }

            for (j in i + 1 until pool.size) {
                val right = pool[j]
                val rightTags = poolTagSets[j]
                val key = canonicalPairKey(left.id, right.id)

                var score = 0.0
                if (leftTags.intersect(rightTags).isEmpty()) {
                    score += 2.2
                }
                if (key !in linkedPairs) {
                    score += 0.6
                }
                val cosine = cosineSimilarity(leftVec, poolVecs[j])
                when {
                    cosine in 0.04..0.55 -> score += cosine * 8.0
                    cosine > 0.72 -> score -= 3.0
                }
                if (leftTags.isNotEmpty() && rightTags.isNotEmpty()) {
                    val rightOneHop = rightTags.flatMapTo(HashSet()) { coNeighbors[it].orEmpty() }
                    val bridgeExists = leftOneHop.any { tagId ->
                        tagId in rightOneHop && tagId !in leftTags && tagId !in rightTags
                    }
                    if (bridgeExists) {
                        score += 1.4
                    }
                }
                val rightInstant = poolInstants[j]
                if (leftInstant != null && rightInstant != null) {
                    val days = abs(ChronoUnit.DAYS.between(leftInstant, rightInstant))
                    if (days >= 7L) {
                        score += 0.6
                    }
                }

                val item = ScoredPair(left = left, right = right, pairKey = key, score = score)
                allCandidates.add(item)
                if (key !in excludePairKeys) {
                    unseenCandidates.add(item)
                }
            }
        }

        val candidates = unseenCandidates.ifEmpty { allCandidates }
        if (candidates.isEmpty()) return null

        val topFive = candidates
            .sortedWith(compareByDescending<ScoredPair> { it.score }.thenBy { it.pairKey })
            .take(5)
        val chosen = topFive[random.nextInt(topFive.size)]
        return chosen.left to chosen.right
    }

    fun computeBridgeInsights(
        left: NoteEntity,
        right: NoteEntity,
        leftTags: List<String>,
        rightTags: List<String>,
        allNotes: List<NoteEntity>,
        noteTagIdsByNote: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
    ): Triple<List<String>, List<String>, Double> {
        val corpusNotes = (allNotes.filter { it.deletedAt == null } + listOf(left, right))
            .distinctBy { it.id }
        val deletedIds = allNotes.filter { it.deletedAt != null }.mapTo(HashSet()) { it.id }

        val df = HashMap<String, Int>()
        val tokensById = HashMap<String, Set<String>>(corpusNotes.size)
        for (note in corpusNotes) {
            val tokens = tokensForNote(note, null)
            tokensById[note.id] = tokens
            for (tok in tokens) {
                df[tok] = (df[tok] ?: 0) + 1
            }
        }
        val totalDocs = corpusNotes.size.coerceAtLeast(1)
        fun idfOf(tok: String): Double {
            val count = (df[tok] ?: 0).coerceAtLeast(1)
            return ln(1.0 + (totalDocs.toDouble() + 0.5) / (count.toDouble() + 0.5))
        }

        val leftTokens = tokensById[left.id].orEmpty()
        val rightTokens = tokensById[right.id].orEmpty()
        val sharedTokens = leftTokens.intersect(rightTokens)

        val topHighIdfSharedTerms = sharedTokens
            .sortedWith(
                compareByDescending<String> { idfOf(it) }
                    .thenByDescending { it.length }
                    .thenBy { it },
            )
            .take(5)

        val unionSize = leftTokens.size + rightTokens.size - sharedTokens.size
        val jaccardSimilarity = if (unionSize > 0) {
            sharedTokens.size.toDouble() / unionSize.toDouble()
        } else {
            0.0
        }

        val leftTagIds = resolveNoteTagIds(left.id, leftTags, noteTagIdsByNote, tagsById)
        val rightTagIds = resolveNoteTagIds(right.id, rightTags, noteTagIdsByNote, tagsById)

        val coCounts = HashMap<String, HashMap<String, Int>>()
        for ((noteId, tagSet) in noteTagIdsByNote) {
            if (noteId in deletedIds || tagSet.size < 2) continue
            for (a in tagSet) {
                val inner = coCounts.getOrPut(a) { HashMap() }
                for (b in tagSet) {
                    if (a != b) {
                        inner[b] = (inner[b] ?: 0) + 1
                    }
                }
            }
        }

        val bridgeTagNames = if (leftTagIds.isNotEmpty() && rightTagIds.isNotEmpty()) {
            val leftOneHop = leftTagIds.flatMapTo(HashSet()) { coCounts[it]?.keys.orEmpty() }
            val rightOneHop = rightTagIds.flatMapTo(HashSet()) { coCounts[it]?.keys.orEmpty() }
            val bridgeIds = (leftOneHop intersect rightOneHop) - leftTagIds - rightTagIds
            bridgeIds
                .mapNotNull { bridgeId ->
                    val tagName = tagsById[bridgeId]?.name ?: return@mapNotNull null
                    val leftWeight = leftTagIds.sumOf { coCounts[it]?.get(bridgeId) ?: 0 }
                    val rightWeight = rightTagIds.sumOf { coCounts[it]?.get(bridgeId) ?: 0 }
                    val strength = minOf(leftWeight, rightWeight) * 10 + (leftWeight + rightWeight)
                    tagName to strength
                }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
                .distinct()
                .take(5)
        } else {
            emptyList()
        }

        return Triple(topHighIdfSharedTerms, bridgeTagNames, jaccardSimilarity)
    }

    private fun isTagMentionedInDraft(draftKey: String, tag: TagEntity): Boolean {
        val candidates = LinkedHashSet<String>()
        val mainKey = tag.nameKey.ifBlank { tagNameKey(tag.name) }
        if (mainKey.isNotEmpty()) candidates.add(mainKey)
        for (part in mainKey.split(ALIAS_SPLIT_REGEX)) {
            if (part.length >= 2) candidates.add(part)
        }
        for (alias in candidates) {
            if (draftKey.contains("#$alias")) return true
            if (ASCII_PURE_REGEX.matches(alias)) {
                if (alias.length >= 2) {
                    val pattern = Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])")
                    if (pattern.containsMatchIn(draftKey)) return true
                }
            } else if (alias.length >= 2 && draftKey.contains(alias)) {
                return true
            }
        }
        return false
    }

    private fun resolveNoteTagIds(
        noteId: String,
        tagNamesOrIds: List<String>,
        noteTagIdsByNote: Map<String, Set<String>>,
        tagsById: Map<String, TagEntity>,
    ): Set<String> {
        val direct = noteTagIdsByNote[noteId].orEmpty()
        if (direct.isNotEmpty() || tagNamesOrIds.isEmpty()) return direct
        val byNameKey = HashMap<String, String>(tagsById.size)
        for ((id, tag) in tagsById) {
            byNameKey.putIfAbsent(tag.nameKey, id)
            byNameKey.putIfAbsent(tagNameKey(tag.name), id)
        }
        val resolved = HashSet<String>()
        for (raw in tagNamesOrIds) {
            if (raw in tagsById) {
                resolved.add(raw)
            } else {
                byNameKey[tagNameKey(raw)]?.let { resolved.add(it) }
            }
        }
        return resolved
    }

    private fun buildTagCoOccurrenceNeighbors(
        noteTagIdsByNote: Map<String, Set<String>>,
        excludedNoteIds: Set<String>,
    ): Map<String, Set<String>> {
        val neighbors = HashMap<String, MutableSet<String>>()
        for ((noteId, tagSet) in noteTagIdsByNote) {
            if (noteId in excludedNoteIds || tagSet.size < 2) continue
            for (a in tagSet) {
                val adj = neighbors.getOrPut(a) { HashSet() }
                for (b in tagSet) {
                    if (a != b) adj.add(b)
                }
            }
        }
        return neighbors
    }

    private fun tokensForNote(note: NoteEntity, chunks: List<NoteChunkEntity>?): Set<String> {
        val freshChunks = chunks?.takeIf { list ->
            list.isNotEmpty() && list.all {
                it.noteRevision == note.revision && it.contentHash == note.contentHash
            }
        }
        if (freshChunks != null) {
            val all = freshChunks
                .flatMap { it.tokenPayload.split(' ') }
                .filter { it.isNotEmpty() }
            if (all.isNotEmpty()) {
                return all.filter { it.length >= 2 }.ifEmpty { all }.toHashSet()
            }
        }
        return tokenizeMeaningful("${note.title.orEmpty()} ${note.searchText}")
    }

    private fun tokenizeMeaningful(text: String): Set<String> {
        val all = LocalRetrievalEngine.tokenizeForIndex(text)
        if (all.isEmpty()) return emptySet()
        return all.filter { it.length >= 2 }.ifEmpty { all }.toHashSet()
    }

    private fun buildTfIdfVector(
        tokens: Collection<String>,
        idfOf: (String) -> Double,
    ): TfIdfVector {
        if (tokens.isEmpty()) return TfIdfVector(emptyMap(), 0.0)
        val weights = HashMap<String, Double>(tokens.size)
        var normSq = 0.0
        for (tok in tokens) {
            if (tok in weights) continue
            val w = idfOf(tok)
            weights[tok] = w
            normSq += w * w
        }
        return TfIdfVector(weights, sqrt(normSq))
    }

    private fun cosineSimilarity(a: TfIdfVector, b: TfIdfVector): Double {
        if (a.norm <= 0.0 || b.norm <= 0.0) return 0.0
        val (smaller, larger) = if (a.weights.size <= b.weights.size) {
            a.weights to b.weights
        } else {
            b.weights to a.weights
        }
        var dot = 0.0
        for ((tok, w) in smaller) {
            val other = larger[tok] ?: continue
            dot += w * other
        }
        return if (dot > 0.0) dot / (a.norm * b.norm) else 0.0
    }

    private fun canonicalPairKey(a: String, b: String): String =
        if (a <= b) "$a|$b" else "$b|$a"

    private fun parseInstant(raw: String?): Instant? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private class TfIdfVector(
        val weights: Map<String, Double>,
        val norm: Double,
    )

    private class ScoredPair(
        val left: NoteEntity,
        val right: NoteEntity,
        val pairKey: String,
        val score: Double,
    )
}
