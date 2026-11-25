package io.github.z3275630ops.floatingnotes.feature.tasks

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderDeliveryPayload

internal class AlarmPlaybackService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentParams: AlarmParams? = null

    private val timeoutRunnable = Runnable {
        handleTimeout()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            val targetId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
            if (targetId == null || activeReminderId == null || activeReminderId == targetId) {
                stopPlaybackAndSelf(detachNotification = false)
            }
            return START_NOT_STICKY
        }

        val params = AlarmParams.fromIntent(intent) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = ReminderScheduler.buildStrongAlarmNotification(
            context = this,
            reminderId = params.reminderId,
            noteId = params.noteId,
            blockId = params.blockId,
            occurrenceId = params.occurrenceId,
            revision = params.revision,
            recurrence = params.recurrence,
            taskText = params.taskText,
            noteLabel = params.noteLabel,
            includeFullScreenIntent = params.includeFullScreenIntent,
            silent = true,
            ongoing = true,
        )
        val notificationId = ReminderScheduler.notificationIdFor(params.reminderId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }

        currentParams = params
        activeReminderId = params.reminderId

        stopMediaAndVibration()
        acquireWakeLock()
        startAlarmAudio()
        startGentleVibration()

        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, AUTO_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        stopMediaAndVibration()
        releaseWakeLock()
        if (currentParams?.reminderId == activeReminderId) {
            activeReminderId = null
        }
        currentParams = null
        super.onDestroy()
    }

    private fun handleTimeout() {
        val params = currentParams
        stopMediaAndVibration()
        releaseWakeLock()
        if (params != null && ReminderScheduler.notificationsGranted(this)) {
            val silentNotification = ReminderScheduler.buildStrongAlarmNotification(
                context = this,
                reminderId = params.reminderId,
                noteId = params.noteId,
                blockId = params.blockId,
                occurrenceId = params.occurrenceId,
                revision = params.revision,
                recurrence = params.recurrence,
                taskText = params.taskText,
                noteLabel = params.noteLabel,
                includeFullScreenIntent = false,
                silent = true,
                ongoing = false,
            )
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.notify(ReminderScheduler.notificationIdFor(params.reminderId), silentNotification)
            }
        }
        stopPlaybackAndSelf(detachNotification = true)
    }

    private fun stopPlaybackAndSelf(detachNotification: Boolean) {
        mainHandler.removeCallbacks(timeoutRunnable)
        stopMediaAndVibration()
        releaseWakeLock()
        activeReminderId = null
        currentParams = null
        stopForeground(if (detachNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FloatingNotes:AlarmPlayback").apply {
                setReferenceCounted(false)
                acquire(AUTO_TIMEOUT_MS + 10_000L)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun startAlarmAudio() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val audioManager = getSystemService(AudioManager::class.java)
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttrs)
            .setAcceptsDelayedFocusGain(false)
            .build()
        audioFocusRequest = focusReq
        runCatching { audioManager?.requestAudioFocus(focusReq) }

        val startedMediaPlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(alarmAttrs)
                setDataSource(this@AlarmPlaybackService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        }.onSuccess { player ->
            mediaPlayer = player
        }.isSuccess

        if (!startedMediaPlayer) {
            runCatching {
                RingtoneManager.getRingtone(this, alarmUri)?.apply {
                    audioAttributes = alarmAttrs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    play()
                    fallbackRingtone = this
                }
            }
        }
    }

    private fun startGentleVibration() {
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0L, 420L, 580L, 420L, 1180L)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator.vibrate(effect, attrs)
            } else {
                val alarmAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, alarmAttrs)
            }
        }
    }

    private fun stopMediaAndVibration() {
        runCatching {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        }
        mediaPlayer = null

        runCatching {
            fallbackRingtone?.stop()
        }
        fallbackRingtone = null

        runCatching {
            val audioManager = getSystemService(AudioManager::class.java)
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null

        runCatching {
            resolveVibrator()?.cancel()
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private data class AlarmParams(
        val reminderId: String,
        val noteId: String,
        val blockId: String,
        val occurrenceId: String?,
        val revision: Long,
        val recurrence: RecurrenceRule,
        val taskText: String,
        val noteLabel: String,
        val includeFullScreenIntent: Boolean,
    ) {
        companion object {
            fun fromIntent(intent: Intent?): AlarmParams? {
                intent ?: return null
                val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return null
                val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID) ?: return null
                val blockId = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID) ?: return null
                return AlarmParams(
                    reminderId = reminderId,
                    noteId = noteId,
                    blockId = blockId,
                    occurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID),
                    revision = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, -1L),
                    recurrence = RecurrenceRule.fromStored(intent.getStringExtra(ReminderScheduler.EXTRA_RECURRENCE)),
                    taskText = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_TEXT).orEmpty(),
                    noteLabel = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_LABEL).orEmpty(),
                    includeFullScreenIntent = intent.getBooleanExtra(EXTRA_INCLUDE_FULL_SCREEN, true),
                )
            }
        }
    }

    companion object {
        const val AUTO_TIMEOUT_MS = 120_000L
        private const val ACTION_START_ALARM = "io.github.z3275630ops.floatingnotes.action.START_ALARM_SERVICE"
        private const val ACTION_STOP_ALARM = "io.github.z3275630ops.floatingnotes.action.STOP_ALARM_SERVICE"
        private const val EXTRA_INCLUDE_FULL_SCREEN = "extra_include_full_screen"

        @Volatile
        var activeReminderId: String? = null
            private set

        fun start(
            context: Context,
            payload: ReminderDeliveryPayload,
            includeFullScreenIntent: Boolean = true,
        ): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AlarmPlaybackService::class.java).apply {
                action = ACTION_START_ALARM
                putExtra(ReminderScheduler.EXTRA_REMINDER_ID, payload.reminder.id)
                putExtra(ReminderScheduler.EXTRA_NOTE_ID, payload.reminder.noteId)
                putExtra(ReminderScheduler.EXTRA_BLOCK_ID, payload.reminder.blockId)
                payload.reminder.occurrenceId?.let { putExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID, it) }
                putExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, payload.reminder.revision)
                putExtra(ReminderScheduler.EXTRA_RECURRENCE, payload.reminder.recurrence)
                putExtra(ReminderScheduler.EXTRA_TASK_TEXT, payload.taskText)
                putExtra(ReminderScheduler.EXTRA_NOTE_LABEL, payload.noteLabel)
                putExtra(EXTRA_INCLUDE_FULL_SCREEN, includeFullScreenIntent)
            }
            return runCatching {
                appContext.startForegroundService(intent)
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            activeReminderId = null
            runCatching {
                appContext.stopService(Intent(appContext, AlarmPlaybackService::class.java))
            }
        }

        fun stopForReminder(context: Context, reminderId: String?) {
            val current = activeReminderId
            if (reminderId == null || current == null || current == reminderId) {
                stop(context)
            }
        }
    }
}
