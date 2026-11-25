package io.github.z3275630ops.floatingnotes.feature.tasks

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCanvasColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTypography
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal data class AlarmAlertPayload(
    val reminderId: String,
    val noteId: String,
    val blockId: String,
    val occurrenceId: String?,
    val revision: Long,
    val taskText: String,
    val noteLabel: String,
) {
    companion object {
        fun fromIntent(intent: Intent?): AlarmAlertPayload? {
            intent ?: return null
            val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return null
            val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID).orEmpty()
            val blockId = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID).orEmpty()
            return AlarmAlertPayload(
                reminderId = reminderId,
                noteId = noteId,
                blockId = blockId,
                occurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID),
                revision = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, -1L),
                taskText = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TEXT).orEmpty(),
                noteLabel = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_LABEL).orEmpty(),
            )
        }
    }
}

class AlarmAlertActivity : ComponentActivity() {

    private var activePayload by mutableStateOf<AlarmAlertPayload?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLockScreenWindowCompat()
        enableEdgeToEdge()

        activePayload = AlarmAlertPayload.fromIntent(intent)
        if (activePayload == null) {
            finish()
            return
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = mintColorScheme(dark),
                typography = mintTypography(),
            ) {
                BackHandler(enabled = true) {
                    // Require explicit choice so ringing alarm isn't accidentally dismissed behind screen.
                }
                activePayload?.let { payload ->
                    AlarmAlertScreen(
                        payload = payload,
                        dark = dark,
                        onComplete = { dispatchActionAndFinish(payload, ReminderScheduler.ACTION_COMPLETE_TASK) },
                        onSnooze10 = {
                            dispatchActionAndFinish(
                                payload = payload,
                                actionName = ReminderScheduler.ACTION_SNOOZE_TASK,
                                snoozeMinutes = ReminderScheduler.ALARM_SNOOZE_MINUTES,
                            )
                        },
                        onDismiss = { dispatchActionAndFinish(payload, ReminderScheduler.ACTION_DISMISS_ALARM) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val updated = AlarmAlertPayload.fromIntent(intent)
        if (updated != null) {
            activePayload = updated
        }
    }

    private fun applyLockScreenWindowCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked == true && !keyguardManager.isKeyguardSecure) {
            runCatching { keyguardManager.requestDismissKeyguard(this, null) }
        }
    }

    private fun dispatchActionAndFinish(
        payload: AlarmAlertPayload,
        actionName: String,
        snoozeMinutes: Long? = null,
    ) {
        AlarmPlaybackService.stopForReminder(this, payload.reminderId)
        ReminderScheduler.cancelNotification(this, payload.reminderId)
        val receiverIntent = ReminderScheduler.actionIntent(
            context = this,
            actionName = actionName,
            reminderId = payload.reminderId,
            noteId = payload.noteId,
            blockId = payload.blockId,
            occurrenceId = payload.occurrenceId,
            revision = payload.revision,
        ).apply {
            if (snoozeMinutes != null) {
                putExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
        sendBroadcast(receiverIntent)
        finishAndRemoveTask()
    }

    companion object {
        fun createIntent(
            context: Context,
            reminderId: String,
            noteId: String,
            blockId: String,
            occurrenceId: String?,
            revision: Long,
            taskText: String,
            noteLabel: String,
        ): Intent = Intent(context, AlarmAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(ReminderScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderScheduler.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderScheduler.EXTRA_BLOCK_ID, blockId)
            occurrenceId?.let { putExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID, it) }
            if (revision > 0L) putExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, revision)
            putExtra(ReminderScheduler.EXTRA_TASK_TEXT, taskText)
            putExtra(ReminderScheduler.EXTRA_NOTE_LABEL, noteLabel)
        }
    }
}

@Composable
private fun AlarmAlertScreen(
    payload: AlarmAlertPayload,
    dark: Boolean,
    onComplete: () -> Unit,
    onSnooze10: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val currentClock = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(mintCanvasColor(dark))
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .testTag("alarm_alert_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = scheme.primaryContainer,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MintIcons.Bell,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "待办强提醒 · $currentClock",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(mintCardSurface(dark))
                    .border(0.8.dp, mintDividerColor(dark), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = if (dark) 0.20f else 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MintIcons.Bell,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Text(
                    text = payload.taskText.ifBlank { "待办事项到期提醒" },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 25.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                val noteSummary = payload.noteLabel.trim()
                if (noteSummary.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = mintSecondaryFill(dark),
                    ) {
                        Text(
                            text = "来自笔记：$noteSummary",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MintPillButton(
                    label = "完成待办",
                    icon = MintIcons.Check,
                    primary = true,
                    dark = dark,
                    onClick = onComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .testTag("alarm_btn_complete"),
                )
                MintPillButton(
                    label = "延后 10 分钟",
                    icon = MintIcons.History,
                    primary = false,
                    dark = dark,
                    onClick = onSnooze10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 54.dp)
                        .testTag("alarm_btn_snooze"),
                )
                MintPillButton(
                    label = "关闭闹钟",
                    icon = MintIcons.Close,
                    primary = false,
                    destructive = false,
                    dark = dark,
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("alarm_btn_dismiss"),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
