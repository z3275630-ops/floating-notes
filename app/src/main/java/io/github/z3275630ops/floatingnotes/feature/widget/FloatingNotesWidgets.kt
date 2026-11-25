package io.github.z3275630ops.floatingnotes.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.MainActivity
import io.github.z3275630ops.floatingnotes.R
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintColorScheme
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object WidgetUpdater {
    fun requestRefreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val quickIds = manager.getAppWidgetIds(ComponentName(appContext, FloatingNotesQuickCaptureWidgetProvider::class.java))
        if (quickIds.isNotEmpty()) {
            val intent = Intent(appContext, FloatingNotesQuickCaptureWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, quickIds)
            }
            appContext.sendBroadcast(intent)
        }
        val reviewIds = manager.getAppWidgetIds(ComponentName(appContext, FloatingNotesReviewWidgetProvider::class.java))
        if (reviewIds.isNotEmpty()) {
            val intent = Intent(appContext, FloatingNotesReviewWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, reviewIds)
            }
            appContext.sendBroadcast(intent)
        }
    }

    internal fun buildLaunchPendingIntent(
        context: Context,
        requestCode: Int,
        actionName: String,
        sessionId: String? = null,
        noteId: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(WidgetContract.EXTRA_WIDGET_ACTION, actionName)
            if (sessionId != null) putExtra(WidgetContract.EXTRA_REVIEW_SESSION_ID, sessionId)
            if (noteId != null) putExtra(WidgetContract.EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class FloatingNotesQuickCaptureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val activeCount = runCatching {
                    NotesDatabase.open(context).notes().countActiveNotes()
                }.getOrDefault(0)
                val privacyOn = WidgetPreferences(context).privacyModeEnabled
                for (widgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
                    views.setTextViewText(
                        R.id.widget_quick_count,
                        if (privacyOn) "隐私模式" else "$activeCount 篇笔记",
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_btn_capture,
                        WidgetUpdater.buildLaunchPendingIntent(context, 4101 + widgetId, WidgetContract.ACTION_QUICK_CAPTURE),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_btn_explore,
                        WidgetUpdater.buildLaunchPendingIntent(context, 4201 + widgetId, WidgetContract.ACTION_OPEN_EXPLORE),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_btn_agent,
                        WidgetUpdater.buildLaunchPendingIntent(context, 4301 + widgetId, WidgetContract.ACTION_OPEN_AGENT),
                    )
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class FloatingNotesReviewWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetContract.ACTION_REFRESH_WIDGET) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FloatingNotesReviewWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = NotesDatabase.open(context)
                val notes = runCatching { db.notes().allNotes() }.getOrDefault(emptyList())
                val latestSession = runCatching { db.discovery().latestReviewSession() }.getOrNull()
                val privacyOn = WidgetPreferences(context).privacyModeEnabled
                val snapshot = FloatingNotesWidgetStateBuilder.buildReviewSnapshot(
                    latestSession = latestSession,
                    notes = notes,
                    privacyModeEnabled = privacyOn,
                )

                val refreshIntent = Intent(context, FloatingNotesReviewWidgetProvider::class.java).apply {
                    action = WidgetContract.ACTION_REFRESH_WIDGET
                }
                val refreshPending = PendingIntent.getBroadcast(
                    context,
                    4400,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                for (widgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_daily_review)
                    views.setTextViewText(R.id.widget_review_privacy_badge, snapshot.privacyBadge)
                    views.setTextViewText(R.id.widget_review_headline, snapshot.headline)
                    views.setTextViewText(R.id.widget_review_excerpt, snapshot.body)
                    views.setTextViewText(R.id.widget_review_updated, snapshot.updatedTimeLabel)
                    views.setOnClickPendingIntent(R.id.widget_review_refresh, refreshPending)
                    views.setOnClickPendingIntent(
                        R.id.widget_review_card_body,
                        WidgetUpdater.buildLaunchPendingIntent(
                            context = context,
                            requestCode = 4500 + widgetId,
                            actionName = WidgetContract.ACTION_OPEN_REVIEW_SESSION,
                            sessionId = snapshot.sessionId,
                            noteId = snapshot.primaryNoteId,
                        ),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_review_btn_capture,
                        WidgetUpdater.buildLaunchPendingIntent(
                            context = context,
                            requestCode = 4600 + widgetId,
                            actionName = WidgetContract.ACTION_QUICK_CAPTURE,
                        ),
                    )
                    appWidgetManager.updateAppWidget(widgetId, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val widgetPrefs = WidgetPreferences(this)
        setContent {
            val dark = isSystemInDarkTheme()
            var privacyEnabled by remember { mutableStateOf(widgetPrefs.privacyModeEnabled) }
            MaterialTheme(
                colorScheme = mintColorScheme(dark),
                typography = mintTypography(),
            ) {
                val scheme = MaterialTheme.colorScheme
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.background)
                        .systemBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "配置桌面回顾小组件",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onBackground,
                    )
                    Text(
                        text = "为保护隐私，默认在桌面和锁屏状态下隐藏正文摘要；点击小组件卡片才会打开浮记查看同一回顾会话。",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        ),
                        color = scheme.onSurfaceVariant,
                    )
                    MintGroupedSection(dark = dark) {
                        MintGroupedRow(
                            label = "开启桌面隐私保护（推荐）",
                            subtitle = "不在桌面卡片直接露正文，仅展示待回顾数量与更新时间",
                            leadingIcon = MintIcons.Settings,
                            dark = dark,
                            trailingContent = {
                                MintSwitch(
                                    checked = privacyEnabled,
                                    onCheckedChange = { privacyEnabled = it },
                                    dark = dark,
                                )
                            },
                            onClick = { privacyEnabled = !privacyEnabled },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    MintPillButton(
                        label = "添加到桌面",
                        icon = MintIcons.Check,
                        dark = dark,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            widgetPrefs.privacyModeEnabled = privacyEnabled
                            WidgetUpdater.requestRefreshAll(this@WidgetConfigActivity)
                            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                setResult(RESULT_OK, resultIntent)
                            } else {
                                setResult(RESULT_OK)
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }
}
