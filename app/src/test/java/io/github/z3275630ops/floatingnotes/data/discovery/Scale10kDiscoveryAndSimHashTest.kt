package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Scale10kDiscoveryAndSimHashTest {

    @Before
    fun setUp() {
        LocalTextIntelligence.clearFeatureCacheForTesting()
    }

    @After
    fun tearDown() {
        LocalTextIntelligence.clearFeatureCacheForTesting()
    }

    private fun synthNote(
        id: String,
        title: String? = null,
        body: String,
        aiPolicy: String = "inherit",
        revision: Long = 1L,
        contentHash: String = "hash-$id-$revision",
        updatedAt: String = "2026-11-20T08:00:00Z",
    ): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = "{}",
        searchText = body,
        authorType = "user",
        sourceType = "manual",
        sourceUrl = null,
        aiPolicy = aiPolicy,
        createdAt = "2026-11-01T08:00:00Z",
        updatedAt = updatedAt,
        revision = revision,
        contentHash = contentHash,
        lastDraftId = "draft-$id",
        committedSeq = revision,
    )

    private fun buildUniqueTopicBody(idx: Int): String {
        val a = SUBJECTS[idx % SUBJECTS.size]
        val b = DOMAINS[(idx / SUBJECTS.size) % DOMAINS.size]
        val c = METHODS[(idx / (SUBJECTS.size * DOMAINS.size)) % METHODS.size]
        val d = OBJECTS[(idx * 7 + 3) % OBJECTS.size]
        val tagA = "topic${idx.toString(36)}"
        val tagB = "sig${((idx * 7919) + 104729).toString(36)}"
        return "$a $b $c $d $tagA $tagB"
    }

    @Test
    fun tenThousandNotesPrecisionExclusionAndWarmCacheSpeedup() {
        // Warm up JVM/regex paths on a tiny batch first, then clear cache before cold measurement.
        val warmupNotes = (0 until 40).map { i ->
            synthNote(id = "warm-$i", body = buildUniqueTopicBody(i + 50_000))
        }
        LocalTextIntelligence.findDuplicateGroups(warmupNotes, maxGroups = 5)
        LocalTextIntelligence.clearFeatureCacheForTesting()

        val corpus = ArrayList<NoteEntity>(10_000)

        // 1) Five embedded duplicate groups (11 notes total)
        val exactA = synthNote(
            id = "dup-exact-1",
            body = "复盘总结：每天坚持整理原子化知识卡片，持续打磨离线检索与增量索引架构！",
            updatedAt = "2026-11-10T08:00:00Z",
        )
        val exactB = synthNote(
            id = "dup-exact-2",
            body = "复盘总结 每天坚持整理原子化知识卡片 持续打磨离线检索与增量索引架构",
            updatedAt = "2026-11-11T08:00:00Z",
        )

        val simA = synthNote(
            id = "dup-sim-1",
            body = "今天下午三点去图书馆借阅分布式系统一致性架构书籍，并整理本周技术复盘清单",
            updatedAt = "2026-11-12T08:00:00Z",
        )
        val simB = synthNote(
            id = "dup-sim-2",
            body = "今天下午四点去图书馆借阅分布式系统一致性架构书籍，再整理本周技术复盘清单",
            updatedAt = "2026-11-13T08:00:00Z",
        )
        val simC = synthNote(
            id = "dup-sim-3",
            body = "今天下午五点去图书馆借阅分布式系统一致性架构书籍，顺便整理本周技术复盘清单",
            updatedAt = "2026-11-14T08:00:00Z",
        )

        val contShort = synthNote(
            id = "dup-cont-1",
            body = "设计本地知识库软件的增量校验同步协议与断点续传机制",
            updatedAt = "2026-11-08T08:00:00Z",
        )
        val contLong = synthNote(
            id = "dup-cont-2",
            body = "设计本地知识库软件的增量校验同步协议与断点续传机制，同时补充基于摘要哈希的离线冲突解决策略",
            updatedAt = "2026-11-09T08:00:00Z",
        )

        val enA = synthNote(
            id = "dup-en-1",
            body = "Incremental SQLite indexing pipeline for ten thousand local notes using precomputed FNV1a hashes",
            updatedAt = "2026-11-15T08:00:00Z",
        )
        val enB = synthNote(
            id = "dup-en-2",
            body = "Incremental SQLite indexing pipeline for ten thousand local notes using precomputed FNV1a tokens",
            updatedAt = "2026-11-16T08:00:00Z",
        )

        val projA = synthNote(
            id = "dup-proj-1",
            body = "季度版本发布上线检查清单：验证数据迁移兼容性、真机性能基线测试与回滚预案演练",
            updatedAt = "2026-11-17T08:00:00Z",
        )
        val projB = synthNote(
            id = "dup-proj-2",
            body = "季度版本发布上线检查清单：核对数据迁移兼容性、真机性能基线测试与回滚预案演练",
            updatedAt = "2026-11-18T08:00:00Z",
        )

        corpus.addAll(
            listOf(
                exactA, exactB,
                simA, simB, simC,
                contShort, contLong,
                enA, enB,
                projA, projB,
            ),
        )

        // 2) Privacy-excluded notes (50 notes): exact clones of duplicate groups + excluded-only duplicate pair + unique excluded
        corpus.add(
            synthNote(
                id = "excl-clone-exact",
                body = exactA.searchText,
                aiPolicy = "exclude",
            ),
        )
        corpus.add(
            synthNote(
                id = "excl-clone-sim",
                body = simA.searchText,
                aiPolicy = "exclude",
            ),
        )
        corpus.add(
            synthNote(
                id = "excl-pair-1",
                body = "绝密私钥托管口令与离线冷钱包助记词归档清单一号副本",
                aiPolicy = "exclude",
            ),
        )
        corpus.add(
            synthNote(
                id = "excl-pair-2",
                body = "绝密私钥托管口令与离线冷钱包助记词归档清单一号副本",
                aiPolicy = "exclude",
            ),
        )
        for (i in 4 until 50) {
            corpus.add(
                synthNote(
                    id = "excl-uniq-$i",
                    body = "隐私隔离笔记第${i}条 ${buildUniqueTopicBody(20_000 + i)}",
                    aiPolicy = "exclude",
                ),
            )
        }

        // 3) Unique topic notes to reach exactly 10,000 total notes
        val uniqueCount = 10_000 - corpus.size
        for (i in 0 until uniqueCount) {
            corpus.add(
                synthNote(
                    id = "uniq-$i",
                    body = buildUniqueTopicBody(i),
                    updatedAt = "2026-11-${((i % 28) + 1).toString().padStart(2, '0')}T06:00:00Z",
                ),
            )
        }
        assertEquals(10_000, corpus.size)

        val coldStartNs = System.nanoTime()
        val coldGroups = LocalTextIntelligence.findDuplicateGroups(
            notes = corpus,
            minSimilarityPercent = 65,
            maxGroups = 20,
        )
        val coldDurationNs = System.nanoTime() - coldStartNs

        val warmStartNs = System.nanoTime()
        val warmGroups = LocalTextIntelligence.findDuplicateGroups(
            notes = corpus,
            minSimilarityPercent = 65,
            maxGroups = 20,
        )
        val warmDurationNs = System.nanoTime() - warmStartNs

        // Verify precision & zero false positives across all 10,000 notes
        val expectedGroupMemberSets = setOf(
            setOf("dup-exact-1", "dup-exact-2"),
            setOf("dup-sim-1", "dup-sim-2", "dup-sim-3"),
            setOf("dup-cont-1", "dup-cont-2"),
            setOf("dup-en-1", "dup-en-2"),
            setOf("dup-proj-1", "dup-proj-2"),
        )
        val actualGroupMemberSets = coldGroups.map { grp ->
            setOf(grp.primaryNote.id) + grp.duplicateNotes.map { it.id }
        }.toSet()
        assertEquals("Actual groups: $actualGroupMemberSets", 5, coldGroups.size)
        assertEquals(expectedGroupMemberSets, actualGroupMemberSets)

        // Verify aiPolicy = "exclude" notes never appear anywhere
        val allGroupedIds = coldGroups.flatMap { grp ->
            listOf(grp.primaryNote) + grp.duplicateNotes
        }
        assertTrue(allGroupedIds.none { it.aiPolicy == "exclude" || it.id.startsWith("excl-") })

        // Verify warm scan matches cold scan identically and runs significantly faster
        assertEquals(coldGroups.size, warmGroups.size)
        assertEquals(coldGroups.map { it.groupId }, warmGroups.map { it.groupId })
        assertEquals(coldGroups.map { it.primaryNote.id }, warmGroups.map { it.primaryNote.id })
        assertEquals(
            coldGroups.map { g -> g.duplicateNotes.map { it.id } },
            warmGroups.map { g -> g.duplicateNotes.map { it.id } },
        )
        assertEquals(coldGroups.map { it.similarityPercent }, warmGroups.map { it.similarityPercent })
        assertEquals(coldGroups.map { it.reason }, warmGroups.map { it.reason })
        assertEquals(coldGroups.map { it.previewText }, warmGroups.map { it.previewText })

        assertTrue(
            "Expected warm scan (${warmDurationNs / 1_000_000}ms) to be faster than cold scan (${coldDurationNs / 1_000_000}ms)",
            warmDurationNs < coldDurationNs,
        )
        assertTrue(LocalTextIntelligence.featureCacheSizeForTesting() <= LocalTextIntelligence.MAX_FEATURE_CACHE_ENTRIES)
    }

    @Test
    fun selectTopKTokensMatchesFullSortWithTiesAndEdgeCases() {
        val tokens = listOf("common", "alpha", "rare_a", "mid", "rare_b", "tie_1", "tie_2", "ultra", "extra", "tail")
        val idf = mapOf(
            "common" to 1.0,
            "alpha" to 4.2,
            "rare_a" to 7.8,
            "mid" to 2.5,
            "rare_b" to 7.8,
            "tie_1" to 5.0,
            "tie_2" to 5.0,
            "ultra" to 9.4,
            "extra" to 3.1,
            "tail" to 0.5,
        )

        val expectedTop8 = tokens.sortedByDescending { idf[it] ?: 0.0 }.take(8)
        val actualTop8 = LocalTextIntelligence.selectTopKTokens(tokens, idf, 8)
        assertEquals(expectedTop8, actualTop8)

        assertEquals(emptyList<String>(), LocalTextIntelligence.selectTopKTokens(emptyList(), idf, 8))
        assertEquals(emptyList<String>(), LocalTextIntelligence.selectTopKTokens(tokens, idf, 0))

        val small = listOf("b", "a", "c")
        val smallIdf = mapOf("a" to 3.0, "b" to 1.0, "c" to 2.0)
        assertEquals(listOf("a", "c", "b"), LocalTextIntelligence.selectTopKTokens(small, smallIdf, 8))
    }

    @Test
    fun featureCacheBoundsCapacityAndRespectsRevisionAndContentHashChanges() {
        val totalNotes = LocalTextIntelligence.MAX_FEATURE_CACHE_ENTRIES + 400
        val largeBatch = (0 until totalNotes).map { i ->
            synthNote(id = "cap-$i", body = buildUniqueTopicBody(i))
        }
        LocalTextIntelligence.findDuplicateGroups(largeBatch, maxGroups = 5)
        assertEquals(
            LocalTextIntelligence.MAX_FEATURE_CACHE_ENTRIES,
            LocalTextIntelligence.featureCacheSizeForTesting(),
        )

        LocalTextIntelligence.clearFeatureCacheForTesting()
        assertEquals(0, LocalTextIntelligence.featureCacheSizeForTesting())

        val n1 = synthNote(
            id = "edit-1",
            body = "完全不相关的一条原始天文观测笔记",
            revision = 1L,
            contentHash = "h-v1",
        )
        val n2 = synthNote(
            id = "edit-2",
            body = "量子计算纠错码表面码拓扑保护实验笔记记录",
            revision = 1L,
            contentHash = "h-target",
        )
        assertTrue(LocalTextIntelligence.findDuplicateGroups(listOf(n1, n2)).isEmpty())

        // Bump revision & contentHash to match n2 -> cache must not return stale features from v1
        val n1Updated = n1.copy(
            searchText = "量子计算纠错码表面码拓扑保护实验笔记记录",
            revision = 2L,
            contentHash = "h-v2",
        )
        val groupsAfterEdit = LocalTextIntelligence.findDuplicateGroups(listOf(n1Updated, n2))
        assertEquals(1, groupsAfterEdit.size)
        assertEquals(100, groupsAfterEdit.single().similarityPercent)
    }

    @Test
    fun warmFeatureCacheStillHonorsCancellationCallback() {
        val n1 = synthNote(id = "c1", body = "用于验证缓存命中时依然会触发取消检查的重复文本段落")
        val n2 = synthNote(id = "c2", body = "用于验证缓存命中时依然会触发取消检查的重复文本段落")

        val initial = LocalTextIntelligence.findDuplicateGroups(listOf(n1, n2))
        assertEquals(1, initial.size)

        var checkCount = 0
        try {
            LocalTextIntelligence.findDuplicateGroups(listOf(n1, n2)) {
                checkCount += 1
                throw IllegalStateException("cancelled-warm")
            }
            fail("Expected checkCancelled exception to propagate on warm cache path")
        } catch (expected: IllegalStateException) {
            assertEquals("cancelled-warm", expected.message)
            assertTrue(checkCount >= 1)
        }
    }

    private companion object {
        val SUBJECTS = listOf(
            "射电望远镜", "潮汐发电站", "珊瑚礁生态", "青铜器铭文", "高山滑雪场",
            "温室水培槽", "古籍修复室", "磁悬浮轴承", "地热热泵站", "风洞实验室",
            "光纤陀螺仪", "极地破冰船", "锂硫蓄电池", "显微切片机", "声呐探测器",
            "天文子午仪", "蜂群无人机", "超导磁体环", "陶瓷窑变釉", "量子随机数",
            "深海热液口", "湿地候鸟群", "风力叶片翼",
        )
        val DOMAINS = listOf(
            "力学仿真", "光谱解析", "路径规划", "热流耦合", "声学建模",
            "化学滴定", "地理测绘", "结构拓扑", "菌株筛选", "矿相鉴定",
            "水文预报", "电网调度", "轨道校准", "信号去噪", "纹理合成",
            "分子对接", "气象同化", "材料疲劳", "流场可视化", "遥感反演",
            "地震波反演", "语义消歧", "微流控芯片", "电磁兼容", "催化动力学",
            "声纹识别", "冷链温控", "柔性电子", "光谱成像",
        )
        val METHODS = listOf(
            "卡尔曼滤波", "有限元网格", "蒙特卡洛树", "自适应采样", "梯度投影法",
            "正交小波变换", "贝叶斯推断", "共轭梯度法", "粒子群寻优", "拉格朗日乘子",
            "马尔可夫链", "奇异值分解", "双线性插值", "谱聚类算法", "非负矩阵分解",
            "稀疏编码器", "局部线性嵌入", "支持向量回归", "高斯过程回归", "图拉普拉斯",
            "快速多极子", "边界元离散", "时间步进积分", "滑模变结构", "模型预测控制",
            "粒子滤波跟踪", "反卷积锐化", "动态时间规整", "核密度估计", "随机森林回归",
            "启发式剪枝",
        )
        val OBJECTS = listOf(
            "钛合金壳体", "碳纤维桁架", "氧化锆陶瓷", "氮化镓器件", "聚酰亚胺膜",
            "硼硅酸盐玻璃", "单晶硅晶圆", "稀土永磁体", "石墨烯气凝胶", "铝锂合金板",
            "蓝宝石基片", "超临界流体", "生物降解酯", "微孔沸石柱", "高熵合金粉",
            "压电微执行器", "硅基光波导", "液态金属环", "钙钛矿薄膜", "纳米银线网",
            "玄武岩纤维", "芳纶蜂窝芯", "氟橡胶密封", "氮化硅轴承", "铌钛超导线",
            "铟镓砷探测", "碳化硅衬底", "聚四氟乙烯", "铁电存储器", "热解石墨片",
            "钨铜复合材料", "镁合金压铸", "锆基非晶态", "磷化铟激光", "硫化镉量子点",
            "聚醚醚酮轴", "二氧化钒膜",
        )
    }
}
