package io.github.z3275630ops.floatingnotes

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.backup.ExternalImportFormat
import io.github.z3275630ops.floatingnotes.data.backup.ExternalNoteImporter
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapEngine
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapMode
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapPreferences
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.explore.CognitiveMapSheet
import io.github.z3275630ops.floatingnotes.feature.explore.CognitiveMapUiState
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CognitiveMapAndImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val compose = createEmptyComposeRule()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun externalZipImportPopulatesRoomV8TagsTasksFtsAndDetectsDuplicates() = runBlocking {
        val zipFile = File(temp.root, "migration-bundle.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("flomo/memos.html"))
            zip.write(
                """
                <html><body>
                <div class="memo">
                    <div class="time">2026-11-20 09:30:00</div>
                    <div class="content"><p>量子笔记导入测试 #flomo/阅读</p></div>
                </div>
                </body></html>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("notes/review.md"))
            zip.write(
                """
                ---
                title: 认知地图复盘
                created_at: 2026-11-21T10:00:00Z
                tags:
                  - 工作/浮记
                ---
                # 认知地图复盘
                整理主题聚落与引用连接逻辑。
                - [ ] 跟进导出任务
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }

        val dbFile = File(temp.root, "import-v8-${newId()}.db")
        val db = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        try {
            val firstPreview = ExternalNoteImporter.previewImport(
                sourceFile = zipFile,
                displayName = "migration-bundle.zip",
            )
            assertEquals(ExternalImportFormat.MARKDOWN_ZIP, firstPreview.format)
            assertEquals(2, firstPreview.totalParsed)
            assertEquals(2, firstPreview.newCount)
            assertEquals(0, firstPreview.duplicateCount)
            assertEquals(2, firstPreview.uniqueTagCount)

            val firstCommit = ExternalNoteImporter.commitImport(
                preview = firstPreview,
                skipDuplicates = true,
                database = db,
            )
            assertEquals(2, firstCommit.importedCount)
            assertEquals(0, firstCommit.skippedDuplicates)
            assertEquals(4, firstCommit.createdTagCount)

            val tags = db.organization().tags()
            val flomoParent = tags.single { it.parentId.isEmpty() && it.name == "flomo" }
            assertTrue(tags.any { it.parentId == flomoParent.id && it.name == "阅读" })
            val workParent = tags.single { it.parentId.isEmpty() && it.name == "工作" }
            assertTrue(tags.any { it.parentId == workParent.id && it.name == "浮记" })

            val allNotes = db.notes().allNotes()
            assertEquals(2, allNotes.size)
            val mdNote = allNotes.single { it.title == "认知地图复盘" }
            val tasks = db.notes().tasks(mdNote.id)
            assertEquals(1, tasks.size)
            assertEquals(false, tasks.single().checked)

            val searchRepo = SearchRepository(db)
            val ftsFlomoHits = searchRepo.observeSmart("量子笔记", NoteScope(), 20).first()
            assertEquals(1, ftsFlomoHits.size)
            assertTrue(ftsFlomoHits.single().searchText.contains("量子笔记导入测试"))

            val ftsMdHits = searchRepo.observeSmart("认知地图", NoteScope(), 20).first()
            assertEquals(1, ftsMdHits.size)
            assertEquals(mdNote.id, ftsMdHits.single().id)

            val existingHashes = allNotes.mapTo(HashSet()) { it.contentHash }
            val existingTexts = allNotes.mapTo(HashSet()) { it.searchText }
            val secondPreview = ExternalNoteImporter.previewImport(
                sourceFile = zipFile,
                displayName = "migration-bundle.zip",
                existingContentHashes = existingHashes,
                existingNormalizedTexts = existingTexts,
            )
            assertEquals(2, secondPreview.totalParsed)
            assertEquals(2, secondPreview.duplicateCount)
            assertEquals(0, secondPreview.newCount)

            val secondCommit = ExternalNoteImporter.commitImport(
                preview = secondPreview,
                skipDuplicates = true,
                database = db,
            )
            assertEquals(0, secondCommit.importedCount)
            assertEquals(2, secondCommit.skippedDuplicates)
            assertEquals(2, db.notes().allNotes().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun cognitiveMapSheetRendersSwitchesModesFiltersMonthsAndRenamesCluster() {
        val notes = List(9) { idx ->
            val month = if (idx < 5) "2026-01" else "2026-02"
            val topic = when (idx % 3) {
                0 -> "架构 索引 性能"
                1 -> "咖啡 手冲 烘焙"
                else -> "旅行 海边 徒步"
            }
            NoteEntity(
                id = "n-$idx",
                title = "笔记 $idx",
                documentJson = "{}",
                searchText = topic,
                sourceType = "manual",
                sourceUrl = null,
                createdAt = "$month-02T08:00:00Z",
                updatedAt = "$month-10T09:00:00Z",
                revision = 1L,
                contentHash = "hash-$idx",
                lastDraftId = "",
                committedSeq = 1L,
            )
        }
        val tags = listOf(TagEntity("t-arch", "", "架构", "架构"))
        val noteTags = listOf(NoteTagEntity("n-0", "t-arch"), NoteTagEntity("n-3", "t-arch"))
        val links = listOf(NoteLinkEntity("n-0", "b-1", "n-1", 1L))
        val prefs = CognitiveMapPreferences(temp.newFolder("map-prefs"))

        fun buildSnapshot(
            mode: CognitiveMapMode,
            month: String?,
            recluster: Boolean,
            names: Map<String, String>,
        ) = CognitiveMapEngine.buildMap(
            notes = notes,
            tags = tags,
            noteTags = noteTags,
            links = links,
            customClusterNames = names,
            mode = mode,
            activeMonthFilter = month,
            reclusterForMonth = recluster,
        )

        var mapState by mutableStateOf(
            CognitiveMapUiState(
                visible = true,
                mode = CognitiveMapMode.TOPIC_MAP,
                snapshot = buildSnapshot(CognitiveMapMode.TOPIC_MAP, null, false, prefs.loadRenames()),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        val backdrop = rememberGlassBackdrop()
                        CognitiveMapSheet(
                            state = mapState,
                            dark = false,
                            backdrop = backdrop,
                            onDismiss = {},
                            onSelectMode = { mode ->
                                val nextSnap = buildSnapshot(
                                    mode,
                                    mapState.activeMonthFilter,
                                    mapState.reclusterForMonth,
                                    mapState.customClusterNames,
                                )
                                mapState = mapState.copy(mode = mode, snapshot = nextSnap)
                            },
                            onSelectMonth = { month ->
                                val nextSnap = buildSnapshot(
                                    mapState.mode,
                                    month,
                                    mapState.reclusterForMonth,
                                    mapState.customClusterNames,
                                )
                                mapState = mapState.copy(activeMonthFilter = month, snapshot = nextSnap)
                            },
                            onToggleRecluster = { recluster ->
                                val nextSnap = buildSnapshot(
                                    mapState.mode,
                                    mapState.activeMonthFilter,
                                    recluster,
                                    mapState.customClusterNames,
                                )
                                mapState = mapState.copy(reclusterForMonth = recluster, snapshot = nextSnap)
                            },
                            onRenameCluster = { clusterId, customName ->
                                val updated = prefs.renameCluster(clusterId, customName)
                                val nextSnap = buildSnapshot(
                                    mapState.mode,
                                    mapState.activeMonthFilter,
                                    mapState.reclusterForMonth,
                                    updated,
                                )
                                mapState = mapState.copy(customClusterNames = updated, snapshot = nextSnap)
                            },
                            onOpenNote = {},
                            onStartWalkFromNote = {},
                        )
                    }
                }
            }

            compose.onNodeWithTag("cognitive-map-sheet").assertIsDisplayed()
            compose.onNodeWithTag("cognitive-map-canvas").assertIsDisplayed()
            compose.onNodeWithTag("map-coverage-note").assertTextContains("已覆盖 9 / 9 篇", substring = true)

            // Switch between TOPIC_MAP and RELATIONSHIP_GRAPH modes
            compose.onNodeWithTag("map-mode-relation").performClick()
            assertEquals(CognitiveMapMode.RELATIONSHIP_GRAPH, mapState.mode)
            compose.onNodeWithTag("map-mode-topic").performClick()
            assertEquals(CognitiveMapMode.TOPIC_MAP, mapState.mode)

            // Filter by month and toggle recluster without crashing
            compose.onNodeWithTag("map-month-2026-01").performClick()
            assertEquals("2026-01", mapState.activeMonthFilter)
            compose.onNodeWithTag("map-recluster-toggle").performClick()
            assertTrue(mapState.reclusterForMonth)
            compose.onNodeWithTag("map-month-all").performClick()
            assertEquals(null, mapState.activeMonthFilter)

            // Tap a node in the first cluster, then rename the cluster and verify persisted label
            val snapshot = mapState.snapshot!!
            val targetCluster = snapshot.clusters.first()
            val targetNode = snapshot.nodes.first { it.clusterId == targetCluster.clusterId }
            val canvasSemantics = compose.onNodeWithTag("cognitive-map-canvas").fetchSemanticsNode()
            val w = canvasSemantics.size.width.toFloat()
            val h = canvasSemantics.size.height.toFloat()
            val padPx = canvasSemantics.layoutInfo.density.density * 18f
            val worldSize = (minOf(w, h) - padPx * 2f).coerceAtLeast(1f)
            val tapOffset = Offset(
                x = w / 2f + (targetNode.x - 0.5f) * worldSize,
                y = h / 2f + (targetNode.y - 0.5f) * worldSize,
            )
            compose.onNodeWithTag("cognitive-map-canvas").performTouchInput { click(tapOffset) }

            compose.onNodeWithTag("map-selection-card").assertIsDisplayed()
            compose.onNodeWithTag("map-rename-cluster-button").performClick()
            compose.onNodeWithTag("map-rename-cluster-input").performTextReplacement("我的知识星图")
            compose.onNodeWithText("保存").performClick()

            compose.onNodeWithTag("map-selected-cluster-title").assertTextContains("我的知识星图")
            compose.onNodeWithTag("map-selected-cluster-machine").assertTextContains(targetCluster.machineLabel, substring = true)
            assertEquals("我的知识星图", prefs.loadRenames()[targetCluster.clusterId])
        }
    }
}
