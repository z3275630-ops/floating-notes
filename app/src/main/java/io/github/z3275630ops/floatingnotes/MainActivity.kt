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
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop

private enum class Lab { Material, Input }

class MainActivity : ComponentActivity() {
    private val notes by viewModels<NotesViewModel>()
    private var deepLinkSeq by mutableLongStateOf(0L)
    private var deepLinkRequest by mutableStateOf<ShellDeepLinkRequest?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Capability checks in UI automatically refresh on ON_RESUME.
    }

    override fun onStart() {
        super.onStart()
        notes.onForeground()
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            deepLinkRequest = extractDeepLink(intent)
        }
        setContent {
            var lab by rememberSaveable { mutableStateOf<Lab?>(null) }
            val pendingTasksCount by notes.pendingTasksCount.collectAsStateWithLifecycle()
            val todoFilterNoteIds by notes.todoFilterNoteIds.collectAsStateWithLifecycle()
            val noteDueBadges by notes.noteDueBadges.collectAsStateWithLifecycle()
            val initialStartOnTasks = remember {
                val initialNoteId = intent?.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID)?.takeIf { it.isNotBlank() }
                val initialOpenTasks = intent?.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_TASKS, false) == true
                initialNoteId == null && (initialOpenTasks || notes.taskPreferences.todoStartupEnabled)
            }

            if (deepLinkRequest != null && lab != null) {
                lab = null
            }

            FloatingNotesTheme {
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
                    )
                }
            }
        }
    }

    private fun extractDeepLink(intent: Intent?): ShellDeepLinkRequest? {
        intent ?: return null
        val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID)?.takeIf { it.isNotBlank() }
        val blockId = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID)?.takeIf { it.isNotBlank() }
        val openTasks = intent.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_TASKS, false)
        if (!openTasks && noteId == null) return null
        deepLinkSeq += 1L
        return ShellDeepLinkRequest(
            seq = deepLinkSeq,
            openTasks = openTasks,
            noteId = noteId,
            blockId = blockId,
        )
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
fun FloatingNotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = mintColorScheme(dark), content = content)
}
