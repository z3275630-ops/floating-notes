package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class CognitiveMapMode { TOPIC_MAP, RELATIONSHIP_GRAPH }

internal enum class MapEdgeKind { EXPLICIT_LINK, SHARED_TAG }

internal data class CognitiveMapCluster(
    val clusterId: String,
    val machineLabel: String,
    val displayLabel: String,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val colorIndex: Int,
    val noteIds: List<String>,
    val topKeywords: List<String>,
)

internal data class CognitiveMapNode(
    val noteId: String,
    val title: String,
    val excerpt: String,
    val clusterId: String,
    val x: Float,
    val y: Float,
    val yearMonth: String,
    val tagNames: List<String>,
    val degree: Int,
    val isHighlighted: Boolean = true,
)

internal data class CognitiveMapEdge(
    val sourceNoteId: String,
    val targetNoteId: String,
    val kind: MapEdgeKind,
    val label: String = "",
)

internal data class CognitiveMapSnapshot(
    val mode: CognitiveMapMode,
    val clusters: List<CognitiveMapCluster>,
    val nodes: List<CognitiveMapNode>,
    val edges: List<CognitiveMapEdge>,
    val availableMonths: List<String>,
    val activeMonthFilter: String?,
    val reclusteredForMonth: Boolean,
    val coveredCount: Int,
    val eligibleCount: Int,
    val samplingNote: String,
    val updatedAtIso: String,
    val isSparse: Boolean,
)

internal object CognitiveMapEngine {
    private const val UNKNOWN_MONTH = "未知"
    private const val SPARSE_CLUSTER_ID = "sparse"
    private const val EPSILON = 1e-12
    private const val NODE_SPACING = 0.024
    private const val HASH_RANGE = 4294967296.0
    private val monthPrefix = Regex("[0-9]{4}-(?:0[1-9]|1[0-2])")

    fun buildMap(
        notes: List<NoteEntity>,
        tags: List<TagEntity>,
        noteTags: List<NoteTagEntity>,
        links: List<NoteLinkEntity>,
        customClusterNames: Map<String, String> = emptyMap(),
        mode: CognitiveMapMode = CognitiveMapMode.TOPIC_MAP,
        activeMonthFilter: String? = null,
        reclusterForMonth: Boolean = false,
        maxSampledNotes: Int = 800,
        nowIso: String = Instant.now().toString(),
    ): CognitiveMapSnapshot {
        val eligible = notes.filter {
            it.deletedAt == null && it.archivedAt == null && it.aiPolicy != "exclude" &&
                (it.searchText.isNotBlank() || !it.title.isNullOrBlank() || it.mediaMask != 0)
        }
        val monthsById = eligible.associate { it.id to yearMonth(it) }
        val availableMonths = monthsById.values.distinct().sortedWith(
            compareBy<String> { it == UNKNOWN_MONTH }.thenByDescending { it },
        )
        val reclustered = reclusterForMonth && activeMonthFilter != null
        val workingPool = if (reclustered) {
            eligible.filter { monthsById.getValue(it.id) == activeMonthFilter }
        } else {
            eligible
        }
        val workingIds = workingPool.mapTo(HashSet()) { it.id }
        val tagsById = tags.associateBy { it.id }
        val tagIdsByNote = noteTags.asSequence()
            .filter { it.noteId in workingIds && it.tagId in tagsById }
            .groupBy({ it.noteId }, { it.tagId })
            .mapValues { (_, ids) -> ids.distinct().sorted() }
        val explicit = links.asSequence()
            .filter { it.sourceId in workingIds && it.targetId in workingIds }
            .map { CognitiveMapEdge(it.sourceId, it.targetId, MapEdgeKind.EXPLICIT_LINK) }
            .distinct()
            .sortedWith(compareBy({ it.sourceNoteId }, { it.targetNoteId }))
            .toList()
        val linkDegree = countDegrees(explicit)
        val limit = maxSampledNotes.coerceAtLeast(0)
        val sampled = if (workingPool.size > limit) {
            sample(workingPool, monthsById, tagIdsByNote, linkDegree, limit)
        } else {
            workingPool.sortedBy { it.id }
        }
        val sampledIds = sampled.mapTo(HashSet()) { it.id }
        val explicitSample = explicit.filter { it.sourceNoteId in sampledIds && it.targetNoteId in sampledIds }
        val edges = if (mode == CognitiveMapMode.RELATIONSHIP_GRAPH) {
            explicitSample + sharedTagEdges(sampled, tagIdsByNote, tagsById, explicitSample)
        } else {
            explicitSample
        }
        val degree = countDegrees(edges)
        val clusters = mutableListOf<CognitiveMapCluster>()
        val points = Array(sampled.size) { Point(0.5, 0.5) }
        val clusterIds = Array(sampled.size) { SPARSE_CLUSTER_ID }
        val sparse = sampled.size < 4
        if (sparse) {
            val positions = when (sampled.size) {
                2 -> listOf(Point(0.32, 0.5), Point(0.68, 0.5))
                3 -> listOf(Point(0.5, 0.28), Point(0.29, 0.67), Point(0.71, 0.67))
                else -> emptyList()
            }
            positions.forEachIndexed { index, point -> points[index] = point }
        } else {
            val vectors = buildVectors(sampled, tagIdsByNote, tagsById)
            val k = sqrt(sampled.size / 2.0).roundToInt().coerceIn(3, 8)
            val groups = cluster(vectors, k)
            groups.forEachIndexed { index, group ->
                val noteIds = group.members.map { sampled[it].id }
                val label = topicLabel(group, vectors, noteIds, tagIdsByNote, tagsById)
                val baseId = "topic_" + LocalRetrievalEngine.contentDigest(
                    (label.keywords.take(2).sorted() + label.tagId.orEmpty())
                        .joinToString("") { "${it.length}:$it" },
                )
                val clusterId = if (clusters.any { it.clusterId == baseId }) {
                    baseId + "_" + LocalRetrievalEngine.contentDigest(noteIds.first())
                } else {
                    baseId
                }
                val angle = 2 * PI * index / groups.size - PI / 2
                val centerX = (0.5 + 0.30 * cos(angle)).toFloat()
                val centerY = (0.5 + 0.30 * sin(angle)).toFloat()
                val radius = (0.11 + 0.13 * sqrt(noteIds.size.toDouble() / sampled.size)).toFloat()
                val mapCluster = CognitiveMapCluster(
                    clusterId, label.name, customClusterNames[clusterId] ?: label.name,
                    centerX, centerY, radius, index, noteIds, label.keywords,
                )
                clusters.add(mapCluster)
                for (member in group.members) {
                    val similarity = cosine(vectors[member], group.centroid).coerceIn(0.0, 1.0)
                    val hash = LocalRetrievalEngine.contentDigest(sampled[member].id)
                    val nodeAngle = 2 * PI * (hash.take(8).toLong(16) / HASH_RANGE)
                    val spread = sqrt(hash.takeLast(8).toLong(16) / HASH_RANGE)
                    val distance = radius * (0.87 - 0.37 * similarity) * spread
                    points[member] = Point(centerX + distance * cos(nodeAngle), centerY + distance * sin(nodeAngle))
                    clusterIds[member] = clusterId
                }
            }
            val clustersById = clusters.associateBy { it.clusterId }
            repel(points, clusterIds.map { clustersById.getValue(it) })
        }

        val nodes = sampled.mapIndexed { index, note ->
            val month = monthsById.getValue(note.id)
            val title = LocalRetrievalEngine.displayNoteTitle(note)
            val excerpt = note.searchText.trim().ifEmpty { title }
            CognitiveMapNode(
                note.id, title, safeUtf16Slice(excerpt, 0, 160), clusterIds[index],
                points[index].x.toFloat(), points[index].y.toFloat(), month,
                tagIdsByNote[note.id].orEmpty().map { tagsById.getValue(it).name }.distinct().sorted(),
                degree[note.id] ?: 0, activeMonthFilter == null || month == activeMonthFilter,
            )
        }
        val covered = nodes.size
        return CognitiveMapSnapshot(
            mode, clusters, nodes, edges, availableMonths, activeMonthFilter, reclustered,
            covered, eligible.size,
            if (covered < workingPool.size) {
                "按时间与关联度抽样覆盖 ${covered}/${eligible.size} 篇"
            } else {
                "已覆盖全部 ${covered} 篇笔记"
            },
            nowIso, sparse,
        )
    }

    fun hitTestNode(
        snapshot: CognitiveMapSnapshot,
        normX: Float,
        normY: Float,
        radius: Float = 0.045f,
    ): CognitiveMapNode? {
        if (radius < 0f) return null
        return snapshot.nodes.asSequence()
            .map { it to distanceSquared(it.x, it.y, normX, normY) }
            .filter { (_, distance) -> distance <= radius * radius }
            .minWithOrNull(compareBy<Pair<CognitiveMapNode, Float>> { !it.first.isHighlighted }
                .thenBy { it.second }.thenBy { it.first.noteId })
            ?.first
    }

    fun hitTestCluster(snapshot: CognitiveMapSnapshot, normX: Float, normY: Float): CognitiveMapCluster? =
        snapshot.clusters.asSequence()
            .map { it to distanceSquared(it.centerX, it.centerY, normX, normY) }
            .filter { (cluster, distance) -> distance <= cluster.radius * cluster.radius }
            .minWithOrNull(compareBy<Pair<CognitiveMapCluster, Float>> { it.second }.thenBy { it.first.clusterId })
            ?.first

    private fun distanceSquared(x: Float, y: Float, otherX: Float, otherY: Float): Float {
        val dx = x - otherX
        val dy = y - otherY
        return dx * dx + dy * dy
    }

    private fun yearMonth(note: NoteEntity): String =
        note.updatedAt.take(7).takeIf(monthPrefix::matches)
            ?: note.createdAt.take(7).takeIf(monthPrefix::matches)
            ?: UNKNOWN_MONTH

    private fun countDegrees(edges: List<CognitiveMapEdge>): Map<String, Int> = buildMap {
        for (edge in edges) {
            put(edge.sourceNoteId, (get(edge.sourceNoteId) ?: 0) + 1)
            if (edge.targetNoteId != edge.sourceNoteId) {
                put(edge.targetNoteId, (get(edge.targetNoteId) ?: 0) + 1)
            }
        }
    }

    private fun sample(
        pool: List<NoteEntity>,
        monthsById: Map<String, String>,
        tagIdsByNote: Map<String, List<String>>,
        linkDegree: Map<String, Int>,
        limit: Int,
    ): List<NoteEntity> {
        val ranked = compareByDescending<NoteEntity> {
            (linkDegree[it.id] ?: 0) + tagIdsByNote[it.id].orEmpty().size
        }.thenBy { it.id }
        val buckets = pool.groupBy { monthsById.getValue(it.id) }.values
            .map { it.sortedWith(ranked) }
            .sortedWith { a, b -> ranked.compare(a.first(), b.first()) }
        val selected = ArrayList<NoteEntity>(limit)
        // One note per month per pass, returning unused slots to the remaining months.
        for (rank in 0 until buckets.maxOf { it.size }) {
            for (bucket in buckets) {
                if (selected.size == limit) return selected.sortedBy { it.id }
                if (rank < bucket.size) selected.add(bucket[rank])
            }
        }
        return selected.sortedBy { it.id }
    }

    private data class TagMatch(
        val left: Int,
        val right: Int,
        var score: Double,
        var strongestTagId: String,
        var strongestWeight: Double,
    )

    private fun sharedTagEdges(
        sampled: List<NoteEntity>,
        tagIdsByNote: Map<String, List<String>>,
        tagsById: Map<String, TagEntity>,
        explicit: List<CognitiveMapEdge>,
    ): List<CognitiveMapEdge> {
        val indexById = sampled.mapIndexed { index, note -> note.id to index }.toMap()
        val size = sampled.size
        val explicitPairs = explicit.mapTo(HashSet()) {
            val a = indexById.getValue(it.sourceNoteId)
            val b = indexById.getValue(it.targetNoteId)
            minOf(a, b).toLong() * size + maxOf(a, b)
        }
        val membersByTag = sortedMapOf<String, MutableList<Int>>()
        sampled.forEachIndexed { index, note ->
            for (tagId in tagIdsByNote[note.id].orEmpty()) {
                membersByTag.getOrPut(tagId) { mutableListOf() }.add(index)
            }
        }
        val candidates = HashMap<Long, TagMatch>()
        for ((tagId, members) in membersByTag) {
            val weight = ln(1 + size.toDouble() / (members.size + 1))
            for (i in members.indices) {
                for (j in i + 1 until members.size) {
                    val left = members[i]
                    val right = members[j]
                    val key = left.toLong() * size + right
                    if (key in explicitPairs) continue
                    val match = candidates.getOrPut(key) { TagMatch(left, right, 0.0, tagId, weight) }
                    match.score += weight
                    if (weight > match.strongestWeight) {
                        match.strongestTagId = tagId
                        match.strongestWeight = weight
                    }
                }
            }
        }
        val ranked = candidates.values.sortedWith(
            compareByDescending<TagMatch> { it.score }.thenBy { it.left }.thenBy { it.right },
        )
        val degree = IntArray(size)
        return buildList {
            for (match in ranked) {
                if (degree[match.left] >= 2 || degree[match.right] >= 2) continue
                add(CognitiveMapEdge(
                    sampled[match.left].id, sampled[match.right].id, MapEdgeKind.SHARED_TAG,
                    tagsById.getValue(match.strongestTagId).name,
                ))
                degree[match.left]++
                degree[match.right]++
            }
        }
    }

    private fun buildVectors(
        notes: List<NoteEntity>,
        tagIdsByNote: Map<String, List<String>>,
        tagsById: Map<String, TagEntity>,
    ): List<Map<String, Double>> {
        val frequencies = notes.map { note ->
            val terms = mutableMapOf<String, Double>()
            fun addTokens(text: String, weight: Double) {
                for (token in LocalRetrievalEngine.tokenizeForIndex(text)) {
                    terms[token] = (terms[token] ?: 0.0) + weight
                }
            }
            addTokens(note.title.orEmpty(), 2.0)
            addTokens(note.searchText, 1.0)
            for (id in tagIdsByNote[note.id].orEmpty()) addTokens(tagsById.getValue(id).name, 2.0)
            terms
        }
        val documentFrequency = HashMap<String, Int>()
        for (terms in frequencies) {
            for (term in terms.keys) documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
        }
        return frequencies.map { terms ->
            normalize(terms.mapValues { (term, tf) ->
                tf * ln(1 + notes.size.toDouble() / (documentFrequency.getValue(term) + 1))
            })
        }
    }

    private data class TopicGroup(val members: List<Int>, val centroid: Map<String, Double>)

    // Spherical k-means, with farthest-first seeds and stable tie-breaking in note ID order.
    private fun cluster(vectors: List<Map<String, Double>>, k: Int): List<TopicGroup> {
        val assignments = IntArray(vectors.size) { -1 }
        val bestSeedSimilarity = DoubleArray(vectors.size) { -1.0 }
        var centroids = List(k) { group ->
            val seed = vectors.indices.asSequence().filter { assignments[it] == -1 }
                .minByOrNull { bestSeedSimilarity[it] }!!
            assignments[seed] = group
            val vector = vectors[seed]
            for (i in vectors.indices) {
                bestSeedSimilarity[i] = maxOf(bestSeedSimilarity[i], cosine(vectors[i], vector))
            }
            vector
        }
        var groups = emptyList<TopicGroup>()
        for (iteration in 0 until 20) {
            val members = List(k) { mutableListOf<Int>() }
            val similarities = DoubleArray(vectors.size)
            var changed = false
            for (i in vectors.indices) {
                var best = assignments[i].coerceAtLeast(0)
                var score = cosine(vectors[i], centroids[best])
                for (candidate in centroids.indices) {
                    if (candidate == best) continue
                    val similarity = cosine(vectors[i], centroids[candidate])
                    if (similarity > score + EPSILON) {
                        best = candidate
                        score = similarity
                    }
                }
                if (best != assignments[i]) changed = true
                assignments[i] = best
                similarities[i] = score
                members[best].add(i)
            }
            for (empty in members.indices.filter { members[it].isEmpty() }) {
                val seed = vectors.indices.asSequence().filter { members[assignments[it]].size > 1 }
                    .minByOrNull { similarities[it] }!!
                members[assignments[seed]].remove(seed)
                members[empty].add(seed)
                assignments[seed] = empty
                changed = true
            }
            groups = members.map { indices ->
                val sum = mutableMapOf<String, Double>()
                for (i in indices) {
                    for ((term, weight) in vectors[i]) sum[term] = (sum[term] ?: 0.0) + weight
                }
                TopicGroup(indices, normalize(sum))
            }
            if (!changed) break
            centroids = groups.map { it.centroid }
        }
        return groups
    }

    private fun normalize(vector: Map<String, Double>): Map<String, Double> {
        val norm = sqrt(vector.values.sumOf { it * it })
        return if (norm == 0.0) vector else vector.mapValues { (_, weight) -> weight / norm }
    }

    private fun cosine(vector: Map<String, Double>, centroid: Map<String, Double>): Double {
        val (smaller, larger) = if (vector.size <= centroid.size) vector to centroid else centroid to vector
        return smaller.entries.sumOf { (term, weight) -> weight * (larger[term] ?: 0.0) }
    }

    private data class TopicLabel(val name: String, val keywords: List<String>, val tagId: String?)

    private fun topicLabel(
        group: TopicGroup,
        vectors: List<Map<String, Double>>,
        noteIds: List<String>,
        tagIdsByNote: Map<String, List<String>>,
        tagsById: Map<String, TagEntity>,
    ): TopicLabel {
        val tagId = noteIds.flatMap { tagIdsByNote[it].orEmpty() }.groupingBy { it }.eachCount().entries
            .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            ?.key
        val termCounts = group.members.flatMap { vectors[it].keys }.groupingBy { it }.eachCount()
        val ranked = group.centroid.keys.sortedWith(
            compareByDescending<String> { it.length > 1 }
                .thenByDescending { termCounts.getValue(it) }
                .thenByDescending { group.centroid.getValue(it) }
                .thenBy { it },
        )
        val keywords = mutableListOf<String>()
        for (term in ranked) {
            if (keywords.size == 3) break
            if (keywords.none { it.contains(term) || term.contains(it) }) keywords.add(term)
        }
        val tagName = tagId?.let { tagsById.getValue(it).name }
        val label = (listOfNotNull(tagName) + keywords).distinct().take(3)
            .joinToString(" · ").ifEmpty { "未提取到主题" }
        return TopicLabel(label, keywords, tagId)
    }

    private data class Point(val x: Double, val y: Double)

    private fun repel(points: Array<Point>, clusters: List<CognitiveMapCluster>) {
        val gridWidth = (1.0 / NODE_SPACING).toInt() + 1
        repeat(15) {
            val grid = arrayOfNulls<MutableList<Int>>(gridWidth * gridWidth)
            for ((i, point) in points.withIndex()) {
                val cell = (point.x / NODE_SPACING).toInt() + gridWidth * (point.y / NODE_SPACING).toInt()
                val occupants = grid[cell] ?: mutableListOf<Int>().also { grid[cell] = it }
                occupants.add(i)
            }
            val moveX = DoubleArray(points.size)
            val moveY = DoubleArray(points.size)
            for ((i, point) in points.withIndex()) {
                val cellX = (point.x / NODE_SPACING).toInt()
                val cellY = (point.y / NODE_SPACING).toInt()
                for (y in maxOf(0, cellY - 1)..minOf(gridWidth - 1, cellY + 1)) {
                    for (x in maxOf(0, cellX - 1)..minOf(gridWidth - 1, cellX + 1)) {
                        for (j in grid[x + gridWidth * y].orEmpty()) {
                            if (j <= i) continue
                            val dx = point.x - points[j].x
                            val dy = point.y - points[j].y
                            val squared = dx * dx + dy * dy
                            if (squared >= NODE_SPACING * NODE_SPACING) continue
                            val distance = sqrt(squared)
                            val push = (NODE_SPACING - distance) * 0.25
                            val angle = (i + j) * 2.399963229728653
                            val shiftX = push * if (distance > EPSILON) dx / distance else cos(angle)
                            val shiftY = push * if (distance > EPSILON) dy / distance else sin(angle)
                            moveX[i] += shiftX
                            moveY[i] += shiftY
                            moveX[j] -= shiftX
                            moveY[j] -= shiftY
                        }
                    }
                }
            }
            for ((i, cluster) in clusters.withIndex()) {
                val dx = points[i].x + moveX[i].coerceIn(-NODE_SPACING, NODE_SPACING) - cluster.centerX
                val dy = points[i].y + moveY[i].coerceIn(-NODE_SPACING, NODE_SPACING) - cluster.centerY
                val distance = sqrt(dx * dx + dy * dy)
                val scale = if (distance > cluster.radius) cluster.radius / distance else 1.0
                points[i] = Point(
                    (cluster.centerX + dx * scale).coerceIn(0.06, 0.94),
                    (cluster.centerY + dy * scale).coerceIn(0.06, 0.94),
                )
            }
        }
    }
}
