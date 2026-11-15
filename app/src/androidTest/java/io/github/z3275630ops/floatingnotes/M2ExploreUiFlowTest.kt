package io.github.z3275630ops.floatingnotes

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.discovery.ActiveWalkSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.DailyReviewSnapshot
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.FindSearchResult
import io.github.z3275630ops.floatingnotes.data.discovery.RetrievalCoverage
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewCardModel
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewConfig
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewSessionEntity
import io.github.z3275630ops.floatingnotes.data.discovery.WalkPostcardSummary
import io.github.z3275630ops.floatingnotes.data.discovery.WalkSessionEntity
import io.github.z3275630ops.floatingnotes.data.discovery.WalkStepEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.explore.EditorEdgeEchoStrip
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreScreen
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreUiState
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class M2ExploreUiFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun sampleNote(id: String, title: String, body: String): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = NoteDocument(listOf(NoteBlock.paragraph(body))).encode(),
        searchText = body,
        sourceType = "manual",
        sourceUrl = null,
        createdAt = "2026-11-10T08:00:00Z",
        updatedAt = "2026-11-12T09:00:00Z",
        revision = 1L,
        contentHash = "h-$id",
        lastDraftId = "d-$id",
        committedSeq = 1L,
    )

    @Test
    fun exploreScreenSwitchesBetweenReviewFindBasketAndWalkPostcard() {
        val n1 = sampleNote("n-ui-1", "设计系统回顾", "Apple 备忘录与薄荷绿分层设计规范")
        val hit1 = EvidenceHit(
            note = n1,
            noteTitle = "设计系统回顾",
            mergedExcerpt = "Apple 备忘录与薄荷绿分层设计规范",
            matchedBlockId = "b1",
            chunkIds = listOf("n-ui-1#chunk_0"),
            reasons = listOf("原句命中", "关键词：薄荷绿"),
            tagNames = listOf("设计"),
            score = 8.4,
        )
        var searchedQuery = ""
        var savedBasket = false
        var savedPostcard = false
        var uiState by mutableStateOf(
            ExploreUiState(
                review = DailyReviewSnapshot(
                    session = ReviewSessionEntity(
                        id = "s-day",
                        localDate = "2026-11-15",
                        profileKey = "default",
                        configHash = "cfg",
                        round = 1,
                        cardIdsJson = "[\"n-ui-1\"]",
                        currentIndex = 0,
                        createdAt = "2026-11-15T08:00:00Z",
                        updatedAt = "2026-11-15T08:00:00Z",
                    ),
                    cards = listOf(
                        ReviewCardModel(
                            note = n1,
                            title = "设计系统回顾",
                            previewExcerpt = n1.searchText,
                            fullText = n1.searchText,
                            tagNames = listOf("设计"),
                            tagIds = listOf("t1"),
                            eventActions = emptySet(),
                        ),
                    ),
                    currentIndex = 0,
                    onThisDay = emptyList(),
                    config = ReviewConfig(),
                    suppressedCount = 0,
                ),
                coverage = RetrievalCoverage(1, 1, 0, "2026-11-15T09:00:00Z"),
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        val backdrop = rememberGlassBackdrop()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                        ExploreScreen(
                            state = uiState,
                            tags = listOf(TagEntity("t1", "", "设计", "设计")),
                            notesCount = 1,
                            linksCount = 0,
                            openTodosCount = 0,
                            dark = false,
                            backdrop = backdrop,
                            onEnsureLoaded = {},
                            onClearMessage = {},
                            onStartExtraReviewRound = {},
                            onSelectReviewIndex = {},
                            onDeferReviewCard = {},
                            onSuppressReviewCard7Days = {},
                            onExcludeReviewCard = {},
                            onExcludeReviewTag = { _, _ -> },
                            onUpdateReviewConfig = {},
                            onRestoreAllExclusions = {},
                            onAnnotateReviewCard = { _, _ -> },
                            onQueryChange = { q -> uiState = uiState.copy(findQuery = q) },
                            onScopeChange = { sc -> uiState = uiState.copy(findScope = sc) },
                            onSearchFind = { q, _ ->
                                searchedQuery = q
                                uiState = uiState.copy(
                                    findResult = FindSearchResult(
                                        query = q,
                                        hits = listOf(hit1),
                                        coverage = RetrievalCoverage(1, 1, 0, "2026-11-15T09:00:00Z"),
                                        extractedKeywords = listOf("薄荷绿"),
                                    ),
                                )
                            },
                            onRefreshIndex = {},
                            onToggleBasket = { hit ->
                                val item = io.github.z3275630ops.floatingnotes.data.discovery.EvidenceBasketItem(
                                    noteId = hit.note.id,
                                    noteRevision = hit.note.revision,
                                    noteTitle = hit.noteTitle,
                                    excerpt = hit.mergedExcerpt,
                                    blockId = hit.matchedBlockId,
                                    reasons = hit.reasons,
                                )
                                uiState = uiState.copy(evidenceBasket = listOf(item))
                            },
                            onClearBasket = { uiState = uiState.copy(evidenceBasket = emptyList()) },
                            onSaveBasketAsNote = { savedBasket = true },
                            onExcludeFindNote = {},
                            onStartWalk = { _, _ ->
                                val s1 = WalkStepEntity("ws1", "walk-1", 0, null, n1.id, "起点", "seed", 0, null, "2026-11-15T10:00:00Z")
                                uiState = uiState.copy(
                                    walk = ActiveWalkSnapshot(
                                        session = WalkSessionEntity("walk-1", n1.id, null, 0, "active", "2026-11-15T10:00:00Z", "2026-11-15T10:00:00Z"),
                                        allSteps = listOf(s1),
                                        currentStep = s1,
                                        currentNote = n1,
                                        currentNoteTags = listOf("设计"),
                                        canStepBack = false,
                                        stepPositionLabel = "第 1 站 · 共 1 站",
                                    ),
                                )
                            },
                            onStepWalkForward = {},
                            onStepWalkBack = {},
                            onSaveWalkMemo = {},
                            onFinishWalkPostcard = {
                                uiState = uiState.copy(
                                    walkPostcard = WalkPostcardSummary(
                                        title = "笔记漫步明信片",
                                        stepCount = 1,
                                        dateSpanLabel = "2026-11-10 · 共 1 站",
                                        pathLines = listOf("1. 设计系统回顾 · 起点"),
                                        reflectionLines = emptyList(),
                                        markdownBody = "漫步明信片（2026-11-10 · 共 1 站）\n1. 设计系统回顾 · 起点",
                                        privacyHidden = false,
                                    ),
                                )
                            },
                            onToggleWalkPrivacy = { priv ->
                                uiState = uiState.copy(
                                    walkPostcard = uiState.walkPostcard?.copy(
                                        privacyHidden = priv,
                                        markdownBody = if (priv) "漫步明信片（隐私保护已隐藏标题与正文）" else "漫步明信片（公开版）",
                                    ),
                                )
                            },
                            onSaveWalkPostcardAsNote = { savedPostcard = true },
                            onDismissWalkPostcard = { uiState = uiState.copy(walkPostcard = null) },
                            onOpenNote = { _, _ -> },
                            onOpenTodos = {},
                        )
                        }
                    }
                }
            }

            compose.onNodeWithTag("review-active-card").assertIsDisplayed()
            compose.onNodeWithTag("review-card-title").assertTextContains("设计系统回顾")

            // Switch to Find tab, query, collect evidence into basket and save
            compose.onNodeWithTag("explore-tab-find").performClick()
            compose.onNodeWithTag("find-query-input").performTextReplacement("薄荷绿设计规范")
            compose.onNodeWithTag("find-submit-button").performClick()
            assertEquals("薄荷绿设计规范", searchedQuery)
            compose.onNodeWithTag("find-hit-card-n-ui-1").assertIsDisplayed()
            compose.onNodeWithTag("find-basket-toggle-n-ui-1").performClick()
            compose.onNodeWithTag("find-evidence-basket-bar").assertIsDisplayed()
            compose.onNodeWithTag("find-basket-save-note").performClick()
            assertTrue(savedBasket)

            // Switch to Walk tab, start walk, finish postcard, toggle privacy, save postcard
            compose.onNodeWithTag("explore-tab-walk").performClick()
            compose.onNodeWithTag("walk-start-button").performScrollTo().performClick()
            compose.onNodeWithTag("walk-active-card").assertIsDisplayed()
            compose.onNodeWithTag("walk-finish-button").performClick()
            compose.onNodeWithTag("walk-postcard-sheet").assertIsDisplayed()
            compose.onNodeWithTag("walk-postcard-privacy-toggle").performClick()
            compose.onNodeWithTag("walk-postcard-body").assertTextContains("隐私保护已隐藏", substring = true)
            compose.onNodeWithTag("walk-postcard-save-note").performClick()
            assertTrue(savedPostcard)
        }
    }

    @Test
    fun editorEdgeEchoStripCanPreviewAndDismissWithoutCrashing() {
        val n1 = sampleNote("n-echo", "历史随笔", "这是之前写过的一段灵感")
        val hit = EvidenceHit(
            note = n1,
            noteTitle = "历史随笔",
            mergedExcerpt = "这是之前写过的一段灵感",
            matchedBlockId = null,
            chunkIds = listOf("c1"),
            reasons = listOf("关键词：灵感"),
            tagNames = emptyList(),
            score = 4.2,
        )
        var activeHit by mutableStateOf<EvidenceHit?>(hit)
        var previewClicked = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(dark = false)) {
                        EditorEdgeEchoStrip(
                            hit = activeHit,
                            dark = false,
                            onPreview = { previewClicked = true },
                            onDismiss = { activeHit = null },
                        )
                    }
                }
            }

            compose.onNodeWithTag("editor-edge-echo").assertIsDisplayed()
            compose.onNodeWithTag("editor-edge-echo").performClick()
            assertTrue(previewClicked)
            compose.onNodeWithTag("editor-edge-echo-dismiss").performClick()
            assertEquals(0, compose.onAllNodesWithTag("editor-edge-echo").fetchSemanticsNodes().size)
        }
    }
}
