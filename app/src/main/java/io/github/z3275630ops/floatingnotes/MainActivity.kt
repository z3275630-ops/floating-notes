package io.github.z3275630ops.floatingnotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import io.github.z3275630ops.floatingnotes.feature.home.NotesShell
import io.github.z3275630ops.floatingnotes.feature.home.ShellDeepLinkRequest
import io.github.z3275630ops.floatingnotes.feature.lab.InputLab
import io.github.z3275630ops.floatingnotes.feature.lab.MaterialLab
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.tasks.TasksScreen
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetContract
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTypography
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop

private enum class Lab { Material, Input }

class MainActivity : ComponentActivity() {
    private val notes by viewModels<NotesViewModel>()
    private var deepLinkSeq by mutableLongStateOf(0L)
    private var deepLinkRequest by mutableStateOf<ShellDeepLinkRequest?>(null)
    private var pendingVoiceStartOnPermission by mutableStateOf(false)
    private var pendingVoiceStartOnForeground by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Capability checks in UI automatically refresh on ON_RESUME.
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        val shouldStart = pendingVoiceStartOnPermission
        pendingVoiceStartOnPermission = false
        val session = notes.editor ?: return@registerForActivityResult
        if (allowed && shouldStart) {
            if (notes.isForeground) {
                notes.startRecording(session)
            } else {
                pendingVoiceStartOnForeground = true
            }
        } else if (!allowed) {
            session.voiceStatus = "麦克风权限未开启，可在系统设置授权后重试"
        }
    }

    override fun onStart() {
        super.onStart()
        notes.onForeground()
        if (pendingVoiceStartOnForeground) {
            pendingVoiceStartOnForeground = false
            notes.editor?.let { session ->
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    notes.startRecording(session)
                }
            }
        }
    }

    override fun onStop() {
        notes.checkpointOnStop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLink(intent)?.let { req ->
            deepLinkRequest = req
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_FloatingNotes)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            deepLinkRequest = extractDeepLink(intent)
        }
        setContent {
            var lab by rememberSaveable { mutableStateOf<Lab?>(null) }
            val appPrefs by notes.appPreferencesSnapshot.collectAsStateWithLifecycle()
            val pendingTasksCount by notes.pendingTasksCount.collectAsStateWithLifecycle()
            val todoFilterNoteIds by notes.todoFilterNoteIds.collectAsStateWithLifecycle()
            val noteDueBadges by notes.noteDueBadges.collectAsStateWithLifecycle()
            val initialStartOnTasks = remember {
                val action = intent?.action
                val hasShortcutCaptureOrVoice = action == WidgetContract.ACTION_SHORTCUT_CAPTURE ||
                    action == WidgetContract.ACTION_SHORTCUT_VOICE
                val hasWidgetAction = !intent?.getStringExtra(WidgetContract.EXTRA_WIDGET_ACTION).isNullOrBlank()
                val initialNoteId = (
                    intent?.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID)
                        ?: intent?.getStringExtra(WidgetContract.EXTRA_NOTE_ID)
                    )?.takeIf { it.isNotBlank() }
                    ?: intent?.data?.takeIf { it.scheme == "floatingnotes" && it.host == "task" }
                        ?.pathSegments?.getOrNull(0)?.takeIf { it.isNotBlank() }
                val initialOpenTasks = intent?.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_TASKS, false) == true ||
                    action == WidgetContract.ACTION_SHORTCUT_TASKS ||
                    intent?.data?.let { it.scheme == "floatingnotes" && it.host == "tasks" } == true
                !hasShortcutCaptureOrVoice &&
                    !hasWidgetAction &&
                    initialNoteId == null &&
                    (initialOpenTasks || notes.appPreferences.openTasksFirst)
            }

            if (deepLinkRequest != null && lab != null) {
                lab = null
            }

            FloatingNotesTheme(fontScaleMultiplier = appPrefs.fontScaleTier.multiplier) {
                BackHandler(enabled = lab != null) { lab = null }
                when (lab) {
                    Lab.Material -> MaterialLab { lab = null }
                    Lab.Input -> InputLab { lab = null }
                    null -> NotesShell(
                        model = notes,
                        labs = {
                            if (BuildConfig.MATERIAL_LAB) {
                                TextButton(onClick = { lab = Lab.Material }) { Text("材质实验") }
                                TextButton(onClick = { lab = Lab.Input }) { Text("输入实验") }
                            }
                        },
                        initialStartOnTasks = initialStartOnTasks,
                        pendingTasksCount = pendingTasksCount,
                        todoFilterNoteIds = todoFilterNoteIds,
                        tasksOverlay = { onClose, onOpenNote ->
                            TasksScreen(
                                repository = notes.repository,
                                preferences = notes.taskPreferences,
                                backdrop = rememberGlassBackdrop(),
                                tier = GlassTier.Enhanced.supportedOn(Build.VERSION.SDK_INT),
                                dark = isSystemInDarkTheme(),
                                onOpenNote = { noteId, blockId ->
                                    notes.queueFocusBlock(blockId)
                                    onOpenNote(noteId)
                                },
                                onClose = onClose,
                                onRequestNotificationPermission = ::requestNotificationPermission,
                            )
                        },
                        noteDueBadges = noteDueBadges,
                        deepLinkRequest = deepLinkRequest,
                        onConsumeDeepLink = { deepLinkRequest = null },
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onRequestVoiceCapture = ::requestVoiceCapture,
                    )
                }
            }
        }
    }

    private fun extractDeepLink(intent: Intent?): ShellDeepLinkRequest? {
        intent ?: return null
        val shortcutAction = intent.action?.takeIf {
            it == WidgetContract.ACTION_SHORTCUT_CAPTURE ||
                it == WidgetContract.ACTION_SHORTCUT_VOICE ||
                it == WidgetContract.ACTION_SHORTCUT_TASKS
        }
        val dataUri = intent.data?.takeIf { it.scheme == "floatingnotes" }
        val uriHost = dataUri?.host
        val uriSegments = dataUri?.pathSegments.orEmpty()
        val uriNoteId = if (uriHost == "task") uriSegments.getOrNull(0)?.takeIf { it.isNotBlank() } else null
        val uriBlockId = if (uriHost == "task") uriSegments.getOrNull(1)?.takeIf { it.isNotBlank() } else null
        val uriOpenTasks = uriHost == "tasks" || uriHost == "task"

        val widgetAction = intent.getStringExtra(WidgetContract.EXTRA_WIDGET_ACTION)?.takeIf { it.isNotBlank() }
        val sessionId = intent.getStringExtra(WidgetContract.EXTRA_REVIEW_SESSION_ID)?.takeIf { it.isNotBlank() }
        val noteId = (
            intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID)
                ?: intent.getStringExtra(WidgetContract.EXTRA_NOTE_ID)
                ?: uriNoteId
            )?.takeIf { it.isNotBlank() }
        val blockId = (
            intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID)
                ?: uriBlockId
            )?.takeIf { it.isNotBlank() }
        val openTasks = intent.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_TASKS, false) ||
            shortcutAction == WidgetContract.ACTION_SHORTCUT_TASKS ||
            uriOpenTasks
        if (!openTasks && noteId == null && widgetAction == null && shortcutAction == null) return null
        deepLinkSeq += 1L
        return ShellDeepLinkRequest(
            seq = deepLinkSeq,
            openTasks = openTasks,
            noteId = noteId,
            blockId = blockId,
            widgetAction = widgetAction,
            reviewSessionId = sessionId,
            shortcutAction = shortcutAction,
        )
    }

    private fun requestVoiceCapture() {
        val session = notes.editor ?: return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (notes.isForeground) {
                pendingVoiceStartOnForeground = false
                notes.startRecording(session)
            } else {
                pendingVoiceStartOnForeground = true
            }
        } else {
            pendingVoiceStartOnPermission = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermission() {
        val capability = ReminderScheduler.capability(this)
        if (capability.notificationsEnabled) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notes.taskPreferences.notificationPromptAttempted
        ) {
            notes.taskPreferences.notificationPromptAttempted = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val settingsIntent = ReminderScheduler.openNotificationSettingsIntent(this)
        runCatching { startActivity(settingsIntent) }.onFailure {
            runCatching { startActivity(ReminderScheduler.openAppDetailsIntent(this)) }
        }
    }
}

@Composable
fun FloatingNotesTheme(
    fontScaleMultiplier: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = mintColorScheme(dark),
        typography = mintTypography(fontScaleMultiplier),
        content = content,
    )
}
