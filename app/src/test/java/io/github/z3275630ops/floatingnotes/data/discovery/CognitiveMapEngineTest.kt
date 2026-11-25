package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.hypot

class CognitiveMapEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun layoutIsReproducibleEvenWhenInputsAreReordered() {
        val notes = topicalNotes()
        val tags = listOf(tag("work", "复盘"), tag("food", "烘焙"))
        val noteTags = listOf(NoteTagEntity("note-01", "work"), NoteTagEntity("note-02", "food"))
        val links = listOf(link("note-00", "note-06"), link("note-06", "note-00"))
        val first = build(notes, tags, noteTags, links)
        assertEquals(first, build(notes, tags, noteTags, links))
        assertEquals(first, build(notes.reversed(), tags.reversed(), noteTags.reversed(), links.reversed()))
        assertEquals(3, first.clusters.size)
        assertEquals(first.clusters.size, first.clusters.map { it.clusterId }.distinct().size)
        assertEquals(notes.map { it.id }.sorted(), first.clusters.flatMap { it.noteIds }.sorted())
        assertEquals(NOW, first.updatedAtIso)
        for (node in first.nodes) {
            val cluster = first.clusters.single { it.clusterId == node.clusterId }
            assertTrue(cluster.centerX in 0.20f..0.80f && cluster.centerY in 0.20f..0.80f)
            assertTrue(cluster.radius in 0.11f..0.24f)
            assertTrue(node.x in 0.06f..0.94f && node.y in 0.06f..0.94f)
            assertTrue(hypot(node.x - cluster.centerX, node.y - cluster.centerY) <= cluster.radius + 0.000001f)
        }
    }

    @Test
    fun timelineHighlightsInPlaceUnlessReclusteringIsRequested() {
        val notes = topicalNotes()
        val full = build(notes)
        val january = build(notes, month = "2026-01")
        val february = build(notes, month = "2026-02")
        assertEquals(listOf("2026-02", "2026-01"), january.availableMonths)
        assertEquals(full.clusters, january.clusters)
        assertEquals(full.clusters, february.clusters)
        assertEquals(full.nodes, january.nodes.map { it.copy(isHighlighted = true) })
        assertEquals(full.nodes, february.nodes.map { it.copy(isHighlighted = true) })
        assertTrue(january.nodes.all { it.isHighlighted == (it.yearMonth == "2026-01") })
        assertTrue(february.nodes.all { it.isHighlighted == (it.yearMonth == "2026-02") })
        assertFalse(january.reclusteredForMonth)

        val reclustered = build(notes, month = "2026-02", recluster = true)
        val onlyFebruary = notes.filter { it.updatedAt.startsWith("2026-02") }
        assertTrue(reclustered.reclusteredForMonth)
        assertTrue(reclustered.nodes.all { it.isHighlighted && it.yearMonth == "2026-02" })
        assertEquals(onlyFebruary.size, reclustered.coveredCount)
        assertEquals(notes.size, reclustered.eligibleCount)
        assertEquals(build(onlyFebruary).nodes, reclustered.nodes)
        assertEquals(build(onlyFebruary).clusters, reclustered.clusters)
        assertNotEquals(february.nodes.filter { it.isHighlighted }, reclustered.nodes)

        val limited = build(notes, month = "2026-02", recluster = true, limit = 4)
        assertEquals(4, limited.coveredCount)
        assertTrue(limited.nodes.all { it.yearMonth == "2026-02" })
    }

    @Test
    fun sparseMapRetainsDirectedLinksAndIgnoresIneligibleNotes() {
        val notes = listOf(
            note("a", title = "仅标题", body = "", updated = "bad"),
            note("b", month = "2026-02").copy(updatedAt = "2026-99-17"),
            note("c").copy(createdAt = "", updatedAt = ""),
            note("empty", title = null, body = "  "),
            note("archived").copy(archivedAt = NOW),
            note("deleted").copy(deletedAt = NOW),
        )
        val result = build(notes, links = listOf(
            link("a", "b"), link("a", "b", block = "second"),
            link("b", "a"), link("b", "c"), link("b", "deleted"), link("archived", "a"),
        ))
        assertTrue(result.isSparse)
        assertTrue(result.clusters.isEmpty())
        assertEquals(3, result.eligibleCount)
        assertEquals("已覆盖全部 3 篇笔记", result.samplingNote)
        assertEquals(listOf("2026-02", "2026-01", "未知"), result.availableMonths)
        assertEquals(listOf("2026-01", "2026-02", "未知"), result.nodes.map { it.yearMonth })
        assertEquals(listOf(2, 3, 1), result.nodes.map { it.degree })
        assertEquals(listOf("a" to "b", "b" to "a", "b" to "c"), result.edges.map {
            it.sourceNoteId to it.targetNoteId
        })
        assertTrue(result.edges.all { it.kind == MapEdgeKind.EXPLICIT_LINK })
        assertTrue(result.nodes.all { it.clusterId == "sparse" && it.x in 0.25f..0.75f && it.y in 0.25f..0.75f })
    }

    @Test
    fun customNameChangesOnlyDisplayLabelAndPersistsAcrossInstances() {
        val notes = topicalNotes()
        val original = build(notes)
        val target = original.clusters.first()
        val dir = temporaryFolder.newFolder()
        val preferences = CognitiveMapPreferences(dir)
        assertTrue(preferences.loadRenames().isEmpty())

        val custom = "我的「主题」 \"A\"\n新行"
        assertEquals(mapOf(target.clusterId to custom), preferences.renameCluster(target.clusterId, "  $custom  "))
        val reopened = CognitiveMapPreferences(dir)
        assertEquals(mapOf(target.clusterId to custom), reopened.loadRenames())
        val renamed = build(notes, names = reopened.loadRenames())
        assertEquals(target.copy(displayLabel = custom), renamed.clusters.first())
        assertEquals(original.nodes, renamed.nodes)

        reopened.renameCluster("other", " 第二个 ")
        assertEquals(mapOf("other" to "第二个"), preferences.renameCluster(target.clusterId, "\t "))
        assertTrue(reopened.clearRename("other").isEmpty())
        assertTrue(CognitiveMapPreferences(dir).loadRenames().isEmpty())
        assertEquals(listOf("cognitive_map_renames.json"), dir.list()?.toList())
    }

    @Test
    fun renameIdentitySurvivesSmallEditsThatKeepRepresentativeKeywords() {
        val notes = topicalNotes()
        val original = build(notes)
        val edited = notes.map { note ->
            if (note.id == "note-04") note.copy(searchText = note.searchText + " 额外补充") else note
        }
        val updated = build(edited)
        assertEquals(
            original.clusters.associate { it.noteIds to it.clusterId },
            updated.clusters.associate { it.noteIds to it.clusterId },
        )
    }

    @Test
    fun relationshipEdgesStaySeparateAndSharedTagDegreeIsBoundedAtBothEnds() {
        val notes = topicalNotes()
        val tags = listOf(tag("common", "常用"), tag("rare", "手冲"))
        val noteTags = notes.map { NoteTagEntity(it.id, "common") } +
            listOf(NoteTagEntity("note-01", "rare"), NoteTagEntity("note-03", "rare"))
        val links = listOf(link("note-01", "note-00"), link("note-01", "note-00", "duplicate"))
        val topic = build(notes, tags, noteTags, links)
        val graph = build(notes, tags, noteTags, links, mode = CognitiveMapMode.RELATIONSHIP_GRAPH)
        val explicit = CognitiveMapEdge("note-01", "note-00", MapEdgeKind.EXPLICIT_LINK)
        assertEquals(listOf(explicit), topic.edges)
        assertEquals(listOf(explicit), graph.edges.filter { it.kind == MapEdgeKind.EXPLICIT_LINK })
        val shared = graph.edges.filter { it.kind == MapEdgeKind.SHARED_TAG }
        assertTrue(CognitiveMapEdge("note-01", "note-03", MapEdgeKind.SHARED_TAG, "手冲") in shared)
        assertTrue(shared.none { setOf(it.sourceNoteId, it.targetNoteId) == setOf("note-00", "note-01") })
        for (node in graph.nodes) {
            assertTrue(shared.count { it.sourceNoteId == node.noteId || it.targetNoteId == node.noteId } <= 2)
            assertEquals(graph.edges.count { it.sourceNoteId == node.noteId || it.targetNoteId == node.noteId }, node.degree)
        }
    }

    @Test
    fun hitTestingPrefersHighlightedNodesAndTheClosestContainingCluster() {
        val result = build(topicalNotes())
        val a = result.nodes[0].copy(x = 0.4f, y = 0.5f, isHighlighted = false)
        val b = result.nodes[1].copy(x = 0.43f, y = 0.5f)
        val c = result.nodes[2].copy(x = 0.44f, y = 0.5f)
        val wide = result.clusters[0].copy(centerX = 0.4f, centerY = 0.5f, radius = 0.2f)
        val near = result.clusters[1].copy(centerX = 0.48f, centerY = 0.5f, radius = 0.15f)
        val overlapping = result.copy(nodes = listOf(a, b, c), clusters = listOf(wide, near))
        assertEquals(b, CognitiveMapEngine.hitTestNode(overlapping, a.x, a.y))
        assertEquals(c, CognitiveMapEngine.hitTestNode(overlapping, c.x, c.y))
        assertEquals(a, CognitiveMapEngine.hitTestNode(overlapping, a.x, a.y, radius = 0.01f))
        assertNull(CognitiveMapEngine.hitTestNode(overlapping, 0f, 0f))
        assertEquals(near, CognitiveMapEngine.hitTestCluster(overlapping, 0.46f, 0.5f))
        assertEquals(wide, CognitiveMapEngine.hitTestCluster(overlapping, 0.25f, 0.5f))
        assertNull(CognitiveMapEngine.hitTestCluster(overlapping, 0f, 0f))
    }

    @Test
    fun stratifiedSampleBalancesMonthsAndPrioritizesConnectedNotes() {
        val notes = List(1500) { index ->
            val month = if (index < 1000) "2026-01" else "2026-02"
            note("n-${index.toString().padStart(4, '0')}", month, body = if (index % 2 == 0) "复盘 效率" else "咖啡 烘焙")
        }
        val tags = listOf(tag("priority", "重点"))
        val noteTags = listOf(NoteTagEntity("n-1499", "priority"))
        val links = listOf(link("n-0999", "n-1499"))
        val sampled = build(notes, tags, noteTags, links, limit = 800)
        assertEquals(800, sampled.coveredCount)
        assertEquals(1500, sampled.eligibleCount)
        assertEquals("按时间与关联度抽样覆盖 800/1500 篇", sampled.samplingNote)
        assertEquals(mapOf("2026-01" to 400, "2026-02" to 400), sampled.nodes.groupingBy { it.yearMonth }.eachCount())
        assertTrue(sampled.nodes.any { it.noteId == "n-0999" })
        assertTrue(sampled.nodes.any { it.noteId == "n-1499" })
        val filtered = build(notes.reversed(), tags, noteTags, links, month = "2026-02", limit = 800)
        assertEquals(sampled.nodes, filtered.nodes.map { it.copy(isHighlighted = true) })
    }

    @Test
    fun aiExcludedNotesDoNotAffectMapCoverageClustersOrEdges() {
        val publicNotes = topicalNotes()
        val privateNote = note("private-note", body = "私密关键词").copy(aiPolicy = "exclude")
        val tags = listOf(tag("shared", "手冲"))
        val publicTags = listOf(NoteTagEntity("note-01", "shared"), NoteTagEntity("note-04", "shared"))
        val publicLinks = listOf(link("note-00", "note-01"))
        val baseline = build(publicNotes, tags, publicTags, publicLinks, mode = CognitiveMapMode.RELATIONSHIP_GRAPH)
        val result = build(
            publicNotes + privateNote,
            tags,
            publicTags + NoteTagEntity(privateNote.id, "shared"),
            publicLinks + link("note-01", privateNote.id) + link(privateNote.id, "note-04"),
            mode = CognitiveMapMode.RELATIONSHIP_GRAPH,
        )
        assertEquals(publicNotes.size, result.eligibleCount)
        assertEquals(baseline.nodes, result.nodes)
        assertEquals(baseline.clusters, result.clusters)
        assertEquals(baseline.edges, result.edges)
    }

    @Test
    fun emptyAndZeroCapacityMapsHaveNoArtificialNodes() {
        assertTrue(build(emptyList()).isSparse)
        val zero = build(topicalNotes(), limit = 0)
        assertTrue(zero.nodes.isEmpty())
        assertTrue(zero.clusters.isEmpty())
        assertTrue(zero.edges.isEmpty())
        assertEquals("按时间与关联度抽样覆盖 0/12 篇", zero.samplingNote)
    }

    private fun build(
        notes: List<NoteEntity>,
        tags: List<TagEntity> = emptyList(),
        noteTags: List<NoteTagEntity> = emptyList(),
        links: List<NoteLinkEntity> = emptyList(),
        names: Map<String, String> = emptyMap(),
        mode: CognitiveMapMode = CognitiveMapMode.TOPIC_MAP,
        month: String? = null,
        recluster: Boolean = false,
        limit: Int = 800,
    ) = CognitiveMapEngine.buildMap(notes, tags, noteTags, links, names, mode, month, recluster, limit, NOW)

    private fun topicalNotes() = List(12) { index ->
        val theme = when (index % 3) {
            0 -> "效率 复盘 计划"
            1 -> "烘焙 咖啡 手冲"
            else -> "海边 徒步 旅行"
        }
        note("note-${index.toString().padStart(2, '0')}", if (index < 6) "2026-01" else "2026-02", theme, theme)
    }

    private fun note(
        id: String,
        month: String = "2026-01",
        title: String? = null,
        body: String = "本地笔记",
        updated: String = "$month-05T10:00:00Z",
    ) = NoteEntity(
        id = id,
        title = title,
        documentJson = "{}",
        searchText = body,
        sourceType = "manual",
        sourceUrl = null,
        createdAt = "$month-01T00:00:00Z",
        updatedAt = updated,
        revision = 1L,
        contentHash = id,
        lastDraftId = "",
        committedSeq = 0L,
    )

    private fun tag(id: String, name: String) = TagEntity(id = id, parentId = "", name = name, nameKey = name)

    private fun link(source: String, target: String, block: String = "block") =
        NoteLinkEntity(source, block, target, null)

    private companion object {
        const val NOW = "2026-11-25T00:00:00Z"
    }
}
