package io.github.z3275630ops.floatingnotes

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.LocalAgentEngine
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionBridge
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionPair
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.agent.AgentUiState
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWorkspace
import io.github.z3275630ops.floatingnotes.feature.capture.EditorSession
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreScreen
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreUiState
import io.github.z3275630ops.floatingnotes.feature.explore.WalkMode
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class M3UiFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun sampleNote(id: String, title: String?, body: String): NoteEntity = NoteEntity(
        id = id,
        title = title,
        documentJson = NoteDocument(listOf(NoteBlock.paragraph(body))).encode(),
        searchText = body,
        sourceType = "manual",
        sourceUrl = null,
        createdAt = "2026-11-10T08:00:00Z",
        updatedAt = "2026-11-12T08:00:00Z",
        revision = 1L,
        contentHash = "h-$id",
        lastDraftId = "d-$id",
        committedSeq = 1L,
    )

    @Test
    fun tappingBlankCanvasFocusesQuickCaptureEditorForTyping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: NotesViewModel
            scenario.onActivity { activity ->
                vm = NotesViewModel(activity.application)
                val draft = DraftEntity(
                    id = "draft-qc-test",
                    slot = "quick-test",
                    noteId = "note-qc-test",
                    baseRevision = 0L,
                    documentJson = NoteDocument.empty().encode(),
                    editSeq = 0L,
                    updatedAt = "2026-11-15T10:00:00Z",
                )
                val session = EditorSession(draft, NoteDocument.empty(), existingNote = false)
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
                            NoteEditor(
                                session = session,
                                model = vm,
                                onSave = {},
                                onDiscard = {},
                                autoFocusKeyboard = true,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            // Click blank area below the first text line -> still focuses capture-editor cleanly
            compose.onNodeWithTag("editor-blank-canvas").performClick()
            compose.onNodeWithTag("capture-editor").performTextInput("快速点击空白区域也能打字")
            compose.onNodeWithTag("capture-editor").assertTextContains("快速点击空白区域也能打字")
        }
    }

    @Test
    fun dualCardWalkExplicitAnalyzeAndFeedbackFlow() {
        val left = sampleNote("n-left", "系统思维笔记", "长链路系统最怕隐藏反馈延迟。")
        val right = sampleNote("n-right", "读书笔记：小步试错", "小步验证能让人在犯错成本极低时修正假设。")
        val pair = DualCollisionPair(
            pairId = "pair-1",
            leftNote = left,
            leftTitle = "系统思维笔记",
            leftBody = "长链路系统最怕隐藏反馈延迟。",
            leftTags = listOf("思维"),
            leftRevision = 1L,
            rightNote = right,
            rightTitle = "读书笔记：小步试错",
            rightBody = "小步验证能让人在犯错成本极低时修正假设。",
            rightTags = listOf("读书"),
            rightRevision = 1L,
        )

        var analyzed = false
        var markedFarFetched = false
        var savedReflection = ""
        var uiState by mutableStateOf(
            ExploreUiState(
                walkMode = WalkMode.Dual,
                dualPair = pair,
            ),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        val backdrop = rememberGlassBackdrop()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            ExploreScreen(
                                state = uiState,
                                tags = listOf(TagEntity("t1", "", "思维", "思维")),
                                notesCount = 2,
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
                                onQueryChange = {},
                                onScopeChange = {},
                                onSearchFind = { _, _ -> },
                                onRefreshIndex = {},
                                onToggleBasket = {},
                                onClearBasket = {},
                                onSaveBasketAsNote = {},
                                onExcludeFindNote = {},
                                onStartWalk = { _, _ -> },
                                onStepWalkForward = {},
                                onStepWalkBack = {},
                                onSaveWalkMemo = {},
                                onFinishWalkPostcard = {},
                                onToggleWalkPrivacy = {},
                                onSaveWalkPostcardAsNote = {},
                                onDismissWalkPostcard = {},
                                onOpenNote = { _, _ -> },
                                onOpenTodos = {},
                                onSelectWalkMode = { wm -> uiState = uiState.copy(walkMode = wm) },
                                onAnalyzeDualPair = {
                                    analyzed = true
                                    uiState = uiState.copy(
                                        dualBridge = DualCollisionBridge(
                                            headline = "两者都关心及时反馈",
                                            points = listOf("短回路能缩短系统延迟"),
                                            engineNotice = "本机规则分析（未调用云端大模型）",
                                        ),
                                    )
                                },
                                onMarkDualFarFetched = {
                                    markedFarFetched = true
                                    uiState = uiState.copy(dualBridgeFarFetched = true)
                                },
                                onSaveDualAsNote = { refl -> savedReflection = refl },
                                selectedSectionIndex = 2,
                                onSelectSection = {},
                            )
                        }
                    }
                }
            }

            compose.onNodeWithTag("walk-mode-dual").assertIsDisplayed()
            compose.onNodeWithTag("dual-card-left").assertIsDisplayed()
            compose.onNodeWithTag("dual-card-right").assertIsDisplayed()
            assertFalse(analyzed) // Must never auto-analyze before explicit click

            compose.onNodeWithTag("dual-reflection-input").performScrollTo().performTextInput("把大系统拆成小验证")
            compose.onNodeWithTag("dual-analyze-button").performScrollTo().performClick()
            assertTrue(analyzed)
            compose.onNodeWithTag("dual-bridge-panel").assertIsDisplayed()

            compose.onNodeWithTag("dual-far-fetched-button").performScrollTo().performClick()
            assertTrue(markedFarFetched)

            compose.onNodeWithTag("dual-save-button").performScrollTo().performClick()
            assertEquals("把大系统拆成小验证", savedReflection)
        }
    }

    @Test
    fun agentWorkspaceSkillRunAndConfirmedSaveIdempotentUi() {
        val run = AgentRunEntity(
            id = "run-1",
            skillId = AgentSkill.DailyReflection.id,
            prompt = AgentSkill.DailyReflection.defaultPrompt,
            scopeJson = "{}",
            status = "completed",
            resultJson = "",
            createdAt = "2026-11-15T10:00:00Z",
            updatedAt = "2026-11-15T10:00:00Z",
        )
        val result = LocalAgentEngine.analyze(
            skill = AgentSkill.DailyReflection,
            prompt = AgentSkill.DailyReflection.defaultPrompt,
            notes = listOf(sampleNote("n-ev", "项目进展", "今天完成键盘焦点修复与双卡设计")),
            chunks = emptyList(),
            tags = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            links = emptyList(),
            scope = io.github.z3275630ops.floatingnotes.data.local.NoteScope(),
            timeRange = io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange.All,
        ).result

        var state by mutableStateOf(AgentUiState())
        var clickedSkill: AgentSkill? = null
        var openedNoteId: String? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = mintColorScheme(false)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            AgentWorkspace(
                                state = state,
                                notesCount = 1,
                                tags = emptyList(),
                                dark = false,
                                onPromptChange = { state = state.copy(promptDraft = it) },
                                onSelectTimeRange = { state = state.copy(timeRange = it) },
                                onSelectTagId = { state = state.copy(selectedTagId = it) },
                                onRunSkill = { skill ->
                                    clickedSkill = skill
                                    state = state.copy(activeRun = run, activeResult = result)
                                },
                                onRunQuestion = {},
                                onStopRun = {},
                                onSelectPastRun = {},
                                onSaveAsNote = {
                                    state = state.copy(
                                        activeActions = listOf(
                                            AgentActionEntity(
                                                actionId = "agent_save_note:run-1",
                                                runId = "run-1",
                                                actionType = "save_note",
                                                targetNoteId = "created-note-1",
                                                sourceRevisionsJson = "{}",
                                                status = "committed",
                                                summary = "已存入笔记",
                                                createdAt = "2026-11-15T10:01:00Z",
                                            ),
                                        ),
                                        statusMessage = "已存入笔记",
                                    )
                                },
                                onExtractTodos = {},
                                onOpenNote = { openedNoteId = it },
                                onClearMessage = {},
                                onQuickCapture = {},
                            )
                        }
                    }
                }
            }

            compose.onNodeWithTag("agent-workspace").assertIsDisplayed()
            compose.onNodeWithTag("agent-skill-daily_reflection").performScrollTo().performClick()
            assertEquals(AgentSkill.DailyReflection, clickedSkill)

            compose.onNodeWithTag("agent-result-card").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("agent-save-note-button").performScrollTo().performClick()
            compose.onNodeWithTag("agent-open-saved-note").performScrollTo().assertIsDisplayed().performClick()
            assertEquals("created-note-1", openedNoteId)
        }
    }
}
