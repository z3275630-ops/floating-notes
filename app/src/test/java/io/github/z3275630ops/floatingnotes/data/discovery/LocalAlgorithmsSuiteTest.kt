package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class LocalAlgorithmsSuiteTest {

    private fun note(
        id: String,
        title: String? = null,
        paragraphs: List<String>,
        todos: List<String> = emptyList(),
        sourceType: String = "manual",
        createdAt: String = "2026-11-10T08:00:00Z",
        updatedAt: String = createdAt,
        deletedAt: String? = null,
        archivedAt: String? = null,
        rawDocumentJson: String? = null,
    ): NoteEntity {
        val docJson = rawDocumentJson ?: run {
            val blocks = buildList<Map<String, Any?>> {
                paragraphs.forEachIndexed { idx, p ->
                    add(linkedMapOf("id" to "p-$id-$idx", "type" to "paragraph", "text" to p))
                }
                todos.forEachIndexed { idx, t ->
                    add(linkedMapOf("id" to "c-$id-$idx", "type" to "checkbox", "text" to t, "checked" to false))
                }
            }
            MiniJson.encode(linkedMapOf("document_schema_version" to 1, "blocks" to blocks))
        }
        return NoteEntity(
            id = id,
            title = title,
            documentJson = docJson,
            searchText = (paragraphs + todos).joinToString("\n"),
            authorType = "user",
            sourceType = sourceType,
            sourceUrl = null,
            createdAt = createdAt,
            updatedAt = updatedAt,
            revision = 1L,
            contentHash = "hash-$id",
            archivedAt = archivedAt,
            deletedAt = deletedAt,
            lastDraftId = "draft-$id",
            committedSeq = 1L,
        )
    }

    private fun reviewEvent(
        id: String,
        noteId: String,
        action: String,
        createdAt: String,
        untilIso: String? = null,
    ): ReviewEventEntity = ReviewEventEntity(
        id = id,
        sessionId = "sess-1",
        noteId = noteId,
        action = action,
        untilIso = untilIso,
        createdAt = createdAt,
    )

    private fun tag(id: String, name: String, parentId: String = ""): TagEntity =
        TagEntity(id = id, parentId = parentId, name = name, nameKey = tagNameKey(name))

    @Test
    fun fsrsReplayComputesIntervalsRetrievabilityAndReasons() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-11-20T08:00:00Z")

        val todayNote = note("n-today", paragraphs = listOf("今天刚记的想法"), createdAt = "2026-11-20T01:00:00Z")
        val recentNote = note("n-recent", paragraphs = listOf("三天前的读书摘要"), createdAt = "2026-11-17T01:00:00Z")
        val sweetUnreviewed = note("n-old", paragraphs = listOf("半个月前的复盘记录"), createdAt = "2026-11-05T01:00:00Z")
        val annotatedDue = note("n-ann", paragraphs = listOf("带批注的重要卡片"), createdAt = "2026-11-01T08:00:00Z")
        val seenEarly = note("n-early", paragraphs = listOf("昨天刚看过一遍"), createdAt = "2026-11-01T08:00:00Z")
        val deferredLapsed = note("n-def", paragraphs = listOf("以前推迟过的待巩固卡片"), createdAt = "2026-11-01T08:00:00Z")
        val excludedIgnored = note("n-excl", paragraphs = listOf("验证 excluded 不改变 FSRS"), createdAt = "2026-11-20T01:00:00Z")

        val events = listOf(
            reviewEvent("e1", "n-ann", "annotated", "2026-11-12T08:00:00Z"),
            reviewEvent("e2", "n-early", "seen", "2026-11-19T08:00:00Z"),
            // Intentionally unordered to verify chronological sorting
            reviewEvent("e3b", "n-def", "deferred", "2026-11-17T08:00:00Z"),
            reviewEvent("e3a", "n-def", "seen", "2026-11-10T08:00:00Z"),
            reviewEvent("e4", "n-excl", "excluded", "2026-11-20T02:00:00Z"),
        )

        val states = FsrsReviewScheduler.computeNoteStates(
            notes = listOf(todayNote, recentNote, sweetUnreviewed, annotatedDue, seenEarly, deferredLapsed, excludedIgnored),
            events = events,
            now = now,
            zoneId = zone,
        )

        val sToday = states.getValue("n-today")
        assertEquals(0, sToday.reps)
        assertEquals(50.0, sToday.urgencyScore, 1e-6)
        assertEquals("今日新记", sToday.scheduleReason)

        val sRecent = states.getValue("n-recent")
        assertEquals(78.0, sRecent.urgencyScore, 1e-6)
        assertEquals("新近记录 · 适合巩固", sRecent.scheduleReason)

        val sOld = states.getValue("n-old")
        assertEquals(92.0, sOld.urgencyScore, 1e-6)
        assertEquals("首次重温 · 写于 15 天前", sOld.scheduleReason)

        val sAnn = states.getValue("n-ann")
        assertEquals(1, sAnn.reps)
        assertEquals(5.0, sAnn.stabilityDays, 1e-6)
        assertEquals(4.4, sAnn.difficulty, 1e-6)
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), sAnn.nextDueAt)
        assertEquals(45.0 / 53.0, sAnn.retrievability, 1e-6)
        assertTrue(sAnn.urgencyScore > 110.0)
        assertEquals("批注巩固到期 · 距上次 8 天", sAnn.scheduleReason)

        val sEarly = states.getValue("n-early")
        assertEquals(1, sEarly.reps)
        assertEquals(3.0, sEarly.stabilityDays, 1e-6)
        assertEquals(27.0 / 28.0, sEarly.retrievability, 1e-6)
        assertTrue(sEarly.urgencyScore < 10.0)
        assertEquals("提前巩固 · 记忆留存 96%", sEarly.scheduleReason)

        val sDef = states.getValue("n-def")
        assertEquals(1, sDef.reps)
        assertEquals(1, sDef.lapses)
        assertEquals(1.95, sDef.stabilityDays, 1e-6)
        assertEquals("此前稍后再看 · 今日重温", sDef.scheduleReason)
        assertTrue(sDef.urgencyScore > 105.0)

        val sExcl = states.getValue("n-excl")
        assertEquals(0, sExcl.reps)
        assertEquals("今日新记", sExcl.scheduleReason)
    }

    @Test
    fun fsrsRankForDailyReviewIsDeterministicAndOrdersByUrgencyBuckets() {
        val zone = ZoneId.of("Asia/Shanghai")
        val localDate = LocalDate.of(2026, 11, 20)
        val now = Instant.parse("2026-11-20T08:00:00Z")

        val overdue = note("n-overdue", paragraphs = listOf("到期旧笔记"), createdAt = "2026-11-01T08:00:00Z")
        val unreviewed = note("n-unrev", paragraphs = listOf("未复习两周旧笔记"), createdAt = "2026-11-05T08:00:00Z")
        val freshToday = note("n-fresh", paragraphs = listOf("今天新记"), createdAt = "2026-11-20T01:00:00Z")
        val early = note("n-early", paragraphs = listOf("昨天刚看过"), createdAt = "2026-11-01T08:00:00Z")

        val events = listOf(
            reviewEvent("ev1", "n-overdue", "seen", "2026-11-10T08:00:00Z"),
            reviewEvent("ev2", "n-early", "seen", "2026-11-19T08:00:00Z"),
        )
        val eligible = listOf(early, freshToday, unreviewed, overdue)

        val run1 = FsrsReviewScheduler.rankForDailyReview(
            eligibleNotes = eligible,
            events = events,
            localDate = localDate,
            profileKey = "default",
            configHash = "5|0|true|false|",
            round = 1,
            now = now,
            zoneId = zone,
            dailyCount = 3,
        )
        val run2 = FsrsReviewScheduler.rankForDailyReview(
            eligibleNotes = eligible.reversed(),
            events = events,
            localDate = localDate,
            profileKey = "default",
            configHash = "5|0|true|false|",
            round = 1,
            now = now,
            zoneId = zone,
            dailyCount = 3,
        )

        assertEquals(listOf("n-overdue", "n-unrev", "n-fresh"), run1.map { it.noteId })
        assertEquals(run1.map { it.noteId }, run2.map { it.noteId })
    }

    @Test
    fun textRankSplitsUrlsCleanlyAndExtractsWithoutHallucinationInOrder() {
        val mixed = "第一句核心要点。\nhttps://example.com/search?q=1;v=2\n第二句补充！第三句追问？\n"
        assertEquals(
            listOf("第一句核心要点。", "第二句补充！", "第三句追问？"),
            LocalTextIntelligence.splitSentences(mixed),
        )

        val allUrls = "https://example.com/a?x=1\nhttps://example.com/b"
        assertEquals(
            listOf("https://example.com/a?x=1", "https://example.com/b"),
            LocalTextIntelligence.splitSentences(allUrls),
        )

        val shortSummary = LocalTextIntelligence.extractKeySentences("今天只写了这一句。没有更多内容。")
        assertFalse(shortSummary.isSummarized)
        assertEquals(2, shortSummary.sentences.size)

        val longArticle = listOf(
            "随手记录一段无关的午后散步天气见闻。",
            "本地向量检索需要平衡分词粒度与索引更新延迟，才能在手机端保持流畅体验。",
            "另外今天顺便整理了书桌上的几根充电线。",
            "当分词粒度过细时，本地检索索引体积膨胀会导致查询延迟上升。",
            "通过增量分块与缓存向量索引，本地检索延迟可以稳定控制在毫秒级。",
            "晚上记得买水果。",
        ).joinToString("\n")

        val summary = LocalTextIntelligence.extractKeySentences(longArticle, maxSentences = 2, maxChars = 180)
        assertTrue(summary.isSummarized)
        assertEquals(2, summary.sentences.size)
        assertTrue(summary.joined.length <= 180)
        for (s in summary.sentences) {
            assertTrue("Sentence must be exact substring of input", longArticle.contains(s))
        }
        val firstPos = longArticle.indexOf(summary.sentences[0])
        val secondPos = longArticle.indexOf(summary.sentences[1])
        assertTrue("Sentences must remain in reading order", firstPos < secondPos)
        assertTrue(summary.joined.contains("检索"))

        // Surrogate-safe truncation check (index 13 falls inside the 2nd emoji surrogate pair)
        val emojiLong = "第一句很长的测试内容😀😀😀😀😀。第二句也是很长的测试内容😀😀😀😀。第三句补充总结。"
        val sliced = LocalTextIntelligence.extractKeySentences(emojiLong, maxSentences = 2, maxChars = 13)
        assertTrue(sliced.joined.length <= 14)
        if (sliced.joined.isNotEmpty()) {
            assertFalse(Character.isHighSurrogate(sliced.joined.last()))
        }
    }

    @Test
    fun simHashDuplicateClusteringHandlesExactContainmentParaphraseAndExclusions() {
        val exactA = note("d-exact-1", paragraphs = listOf("复盘：每天坚持写卡片笔记，建立自己的知识网络！"), updatedAt = "2026-11-10T08:00:00Z")
        val exactB = note("d-exact-2", paragraphs = listOf("复盘 每天坚持写卡片笔记 建立自己的知识网络"), updatedAt = "2026-11-11T08:00:00Z")

        val containShort = note("d-cont-1", paragraphs = listOf("设计本地笔记软件的增量同步协议"), updatedAt = "2026-11-08T08:00:00Z")
        val containLong = note(
            "d-cont-2",
            paragraphs = listOf("设计本地笔记软件的增量同步协议，支持断点续传、SHA256校验与离线冲突解决机制"),
            updatedAt = "2026-11-09T08:00:00Z",
        )

        val simA = note("d-sim-1", paragraphs = listOf("今天下午三点去图书馆借阅系统架构书籍，并整理本周项目复盘清单"), updatedAt = "2026-11-12T08:00:00Z")
        val simB = note("d-sim-2", paragraphs = listOf("今天下午四点去图书馆借阅系统架构书籍，再整理本周项目复盘清单"), updatedAt = "2026-11-13T08:00:00Z")

        val unique = note("d-uniq", paragraphs = listOf("周末去菜市场买番茄、土豆、牛肉和香菜做炖汤"))
        val tooShort = note("d-short", paragraphs = listOf("买牛奶"))
        val trashed = note("d-trash", paragraphs = listOf("复盘：每天坚持写卡片笔记，建立自己的知识网络！"), deletedAt = "2026-11-15T00:00:00Z")
        val archived = note("d-arch", paragraphs = listOf("复盘：每天坚持写卡片笔记，建立自己的知识网络！"), archivedAt = "2026-11-15T00:00:00Z")
        val generated = note("d-gen", paragraphs = listOf("复盘：每天坚持写卡片笔记，建立自己的知识网络！"), sourceType = "merge_cleanup")

        val all = listOf(exactA, exactB, containShort, containLong, simA, simB, unique, tooShort, trashed, archived, generated)
        val groups = LocalTextIntelligence.findDuplicateGroups(all)

        assertEquals(3, groups.size)

        val exactGroup = groups.first { it.similarityPercent == 100 }
        assertEquals("内容完全相同", exactGroup.reason)
        assertEquals("d-exact-2", exactGroup.primaryNote.id)
        assertEquals(listOf("d-exact-1"), exactGroup.duplicateNotes.map { it.id })

        val containGroup = groups.first { it.reason == "一条几乎包含另一条" }
        assertEquals("d-cont-2", containGroup.primaryNote.id)
        assertEquals(listOf("d-cont-1"), containGroup.duplicateNotes.map { it.id })

        val simGroup = groups.first { it.reason == "内容高度相似" }
        assertEquals("d-sim-2", simGroup.primaryNote.id)
        assertEquals(listOf("d-sim-1"), simGroup.duplicateNotes.map { it.id })

        // Verify SimHash properties directly
        val tokens1 = LocalRetrievalEngine.tokenizeForIndex(simA.searchText)
        val tokens2 = LocalRetrievalEngine.tokenizeForIndex(simB.searchText)
        val h1 = LocalTextIntelligence.computeSimHash64(tokens1)
        val h2 = LocalTextIntelligence.computeSimHash64(tokens2)
        assertEquals(0, LocalTextIntelligence.hammingDistance(h1, h1))
        assertTrue(LocalTextIntelligence.hammingDistance(h1, h2) <= 16)
    }

    @Test
    fun buildMergedParagraphsPreservesUniqueContentAcrossDocumentAndSearchTextFallback() {
        val primaryDoc = MiniJson.encode(
            linkedMapOf(
                "document_schema_version" to 1,
                "blocks" to listOf(
                    linkedMapOf("id" to "b1", "type" to "paragraph", "text" to "第一段核心观点。"),
                    linkedMapOf("id" to "b2", "type" to "checkbox", "text" to "待办事项：补齐单元测试", "checked" to false),
                    linkedMapOf("id" to "b3", "type" to "reference", "target_note_id" to "other", "target_revision" to 1, "label" to "@引用标题"),
                ),
            ),
        )
        val dupDoc = MiniJson.encode(
            linkedMapOf(
                "document_schema_version" to 1,
                "blocks" to listOf(
                    linkedMapOf("id" to "d1", "type" to "paragraph", "text" to "第一段核心观点"),
                    linkedMapOf("id" to "d2", "type" to "paragraph", "text" to "重复笔记里独有的补充结论。"),
                ),
            ),
        )

        val primary = note("m-1", paragraphs = listOf("ignored"), rawDocumentJson = primaryDoc)
        val dup1 = note("m-2", paragraphs = listOf("ignored"), rawDocumentJson = dupDoc)
        val dup2 = note("m-3", paragraphs = listOf("待办事项：补齐单元测试", "第三条遗漏灵感"), rawDocumentJson = "{}")

        val merged = LocalTextIntelligence.buildMergedParagraphs(primary, listOf(dup1, dup2))
        assertEquals(
            listOf(
                "第一段核心观点。",
                "待办事项：补齐单元测试",
                "重复笔记里独有的补充结论。",
                "第三条遗漏灵感",
            ),
            merged,
        )
    }

    @Test
    fun recommendTagsCombinesMentionKnnAndCoOccurrenceSignals() {
        val tArch = tag("t-arch", "架构")
        val tAlgo = tag("t-algo", "算法")
        val tCo = tag("t-co", "工程复盘")
        val tCook = tag("t-cook", "做饭")
        val tAi = tag("t-ai", "AI")
        val tAttached = tag("t-rev", "复盘")

        val n1 = note("n1", paragraphs = listOf("分布式缓存一致性与读写分离优化方案"))
        val n2 = note("n2", paragraphs = listOf("分布式缓存穿透击穿与雪崩治理策略"))
        val n3 = note("n3", paragraphs = listOf("分布式缓存架构演进与淘汰算法总结"))
        val n4 = note("n4", paragraphs = listOf("番茄牛腩和土豆炖鸡周末菜单"))
        val n5 = note("n5", paragraphs = listOf("每周站会同步与研发排期协同总结"))
        val n6 = note("n6", paragraphs = listOf("季度里程碑回顾与跨团队沟通纪要"))
        val tags = listOf(tArch, tAlgo, tCo, tCook, tAi, tAttached)
        val noteTags = mapOf(
            "n1" to setOf("t-algo"),
            "n2" to setOf("t-algo"),
            "n3" to setOf("t-algo"),
            "n4" to setOf("t-cook"),
            "n5" to setOf("t-rev", "t-co"),
            "n6" to setOf("t-rev", "t-co"),
        )

        val recs = LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = "今天整理微服务架构与分布式缓存一致性问题",
            currentNoteId = null,
            attachedTagIds = setOf("t-rev"),
            allNotes = listOf(n1, n2, n3, n4, n5, n6),
            allChunks = emptyList(),
            tags = tags,
            noteTagIdsByNote = noteTags,
            limit = 4,
        )

        val recIds = recs.map { it.tag.id }
        assertTrue(recIds.contains("t-arch"))
        assertTrue(recIds.contains("t-algo"))
        assertTrue(recIds.contains("t-co"))
        assertFalse("Already attached tag must be excluded", recIds.contains("t-rev"))
        assertFalse("Unrelated tag must not be recommended", recIds.contains("t-cook"))
        assertFalse("Unmentioned AI tag must not be recommended", recIds.contains("t-ai"))

        assertEquals("正文提及", recs.first { it.tag.id == "t-arch" }.reason)
        assertEquals("相似笔记常用", recs.first { it.tag.id == "t-algo" }.reason)
        assertEquals("常与 #复盘 同现", recs.first { it.tag.id == "t-co" }.reason)

        // Verify ASCII word boundary rejects 'again' and accepts standalone 'AI'
        val falsePositiveRecs = LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = "again not mentioning artificial intelligence",
            currentNoteId = null,
            attachedTagIds = emptySet(),
            allNotes = listOf(n1, n2, n3, n4, n5, n6),
            allChunks = emptyList(),
            tags = tags,
            noteTagIdsByNote = noteTags,
        )
        assertFalse("Word-boundary check must reject 'again'", falsePositiveRecs.any { it.tag.id == "t-ai" })

        val aiRecs = LocalTagAndGraphEngine.recommendTagsForDraft(
            draftText = "今天测试本地 AI 检索算法",
            currentNoteId = null,
            attachedTagIds = emptySet(),
            allNotes = listOf(n1, n2, n3, n4, n5, n6),
            allChunks = emptyList(),
            tags = tags,
            noteTagIdsByNote = noteTags,
        )
        assertTrue(aiRecs.any { it.tag.id == "t-ai" && it.reason == "正文提及" })
    }

    @Test
    fun smartDualCollisionAndBridgeInsightsUseCrossTopicSweetSpotAndTwoHopGraph() {
        val tRead = tag("t-read", "阅读")
        val tWork = tag("t-work", "工作")
        val tSys = tag("t-sys", "系统思考")
        val tCook = tag("t-cook", "做饭")
        val tagsById = listOf(tRead, tWork, tSys, tCook).associateBy { it.id }

        // Single note -> null
        val single = note("only", paragraphs = listOf("唯一一条笔记"))
        assertNull(
            LocalTagAndGraphEngine.selectBestDualCollisionPair(
                eligibleNotes = listOf(single),
                chunksByNote = emptyMap(),
                noteTagIdsByNote = emptyMap(),
                tagsById = emptyMap(),
                links = emptyList(),
                excludePairKeys = emptySet(),
                random = Random(1),
            ),
        )

        // Small 2-note pool falls back gracefully even if excluded
        val smallA = note("s1", paragraphs = listOf("第一条笔记内容"))
        val smallB = note("s2", paragraphs = listOf("第二条笔记内容"))
        val smallPair = LocalTagAndGraphEngine.selectBestDualCollisionPair(
            eligibleNotes = listOf(smallA, smallB),
            chunksByNote = emptyMap(),
            noteTagIdsByNote = emptyMap(),
            tagsById = emptyMap(),
            links = emptyList(),
            excludePairKeys = setOf("s1|s2"),
            random = Random(1),
        )
        assertNotNull(smallPair)
        assertNotEquals(smallPair!!.first.id, smallPair.second.id)

        // 3-note pool honors excludePairKeys strictly when unseen pairs remain
        val smallC = note("s3", paragraphs = listOf("第三条笔记内容"))
        val forcedPair = LocalTagAndGraphEngine.selectBestDualCollisionPair(
            eligibleNotes = listOf(smallA, smallB, smallC),
            chunksByNote = emptyMap(),
            noteTagIdsByNote = emptyMap(),
            tagsById = emptyMap(),
            links = emptyList(),
            excludePairKeys = setOf("s1|s2", "s1|s3"),
            random = Random(1),
        )
        assertNotNull(forcedPair)
        assertEquals(setOf("s2", "s3"), setOf(forcedPair!!.first.id, forcedPair.second.id))

        // Rich scoring test
        val nLeft = note(
            id = "n-left",
            paragraphs = listOf("读《系统之美》：信息反馈回路与认知延迟如何影响长期决策"),
            createdAt = "2026-10-01T08:00:00Z",
        )
        val nSweet = note(
            id = "n-sweet",
            paragraphs = listOf("优化服务异步反馈回路与请求队列延迟指标监控"),
            createdAt = "2026-11-15T08:00:00Z",
        )
        val nDup = note(
            id = "n-dup",
            paragraphs = listOf("读《系统之美》：信息反馈回路与认知延迟如何影响长期决策思考"),
            createdAt = "2026-10-02T08:00:00Z",
        )
        val nCook = note(
            id = "n-cook",
            paragraphs = listOf("红烧排骨加冰糖炒糖色配米饭"),
            createdAt = "2026-10-03T08:00:00Z",
        )
        val bridgeHelper1 = note("b1", paragraphs = listOf("阅读复盘方法论笔记"), createdAt = "2026-10-10T08:00:00Z")
        val bridgeHelper2 = note("b2", paragraphs = listOf("工程复盘系统方法论"), createdAt = "2026-10-12T08:00:00Z")

        val noteTagIds = mapOf(
            "n-left" to setOf("t-read"),
            "n-sweet" to setOf("t-work"),
            "n-dup" to setOf("t-read"),
            "n-cook" to setOf("t-cook"),
            "b1" to setOf("t-read", "t-sys"),
            "b2" to setOf("t-work", "t-sys"),
        )
        val links = listOf(NoteLinkEntity("n-left", "blk-1", "n-dup", 1L))

        val bestPair = LocalTagAndGraphEngine.selectBestDualCollisionPair(
            eligibleNotes = listOf(nLeft, nSweet, nDup, nCook),
            chunksByNote = emptyMap(),
            noteTagIdsByNote = noteTagIds,
            tagsById = tagsById,
            links = links,
            excludePairKeys = emptySet(),
            random = Random(42),
        )
        assertNotNull(bestPair)
        val pickedIds = setOf(bestPair!!.first.id, bestPair.second.id)
        assertFalse("Should not pick near-duplicate linked pair", pickedIds == setOf("n-left", "n-dup"))

        val (sharedTerms, bridgeTags, jaccard) = LocalTagAndGraphEngine.computeBridgeInsights(
            left = nLeft,
            right = nSweet,
            leftTags = listOf("阅读"),
            rightTags = listOf("工作"),
            allNotes = listOf(nLeft, nSweet, nDup, nCook, bridgeHelper1, bridgeHelper2),
            noteTagIdsByNote = noteTagIds,
            tagsById = tagsById,
        )
        assertTrue(sharedTerms.any { it.contains("反馈") || it.contains("回路") || it.contains("延迟") })
        assertEquals(listOf("系统思考"), bridgeTags)
        assertTrue(jaccard > 0.0 && jaccard < 1.0)

        // Verify >70 note cap handles large pools cleanly and skips excluded pairs
        val largePool = (1..85).map { idx ->
            note(
                id = "large-$idx",
                paragraphs = listOf("第${idx}条笔记记录项目进度与系统架构第${idx % 7}轮反思"),
                createdAt = "2026-11-${(idx % 28 + 1).toString().padStart(2, '0')}T08:00:00Z",
            )
        }
        val largePick = LocalTagAndGraphEngine.selectBestDualCollisionPair(
            eligibleNotes = largePool,
            chunksByNote = emptyMap(),
            noteTagIdsByNote = emptyMap(),
            tagsById = tagsById,
            links = emptyList(),
            excludePairKeys = emptySet(),
            random = Random(9),
        )
        assertNotNull(largePick)
        assertNotEquals(largePick!!.first.id, largePick.second.id)
    }
}
