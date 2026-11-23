package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskUrgencyEngine
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskUrgencyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PinyinFuzzyAndUrgencyTest {

    private fun taskItem(
        blockId: String,
        text: String,
        dueDate: String? = null,
        noteUpdatedAt: String = "2026-11-14T08:00:00Z",
        reminderLocalDate: String? = null,
        reminderLocalTime: String? = null,
        triggerInstantMs: Long? = null,
        reminderStatus: ReminderStatus? = null,
        recurrence: RecurrenceRule = RecurrenceRule.NONE,
        snoozedUntilMs: Long? = null,
    ): TaskItemProjection = TaskItemProjection(
        noteId = "note-1",
        blockId = blockId,
        text = text,
        checked = false,
        dueDate = dueDate,
        occurrenceId = null,
        noteTitle = "测试笔记",
        notePreview = text,
        noteArchived = false,
        noteUpdatedAt = noteUpdatedAt,
        reminderId = if (reminderStatus != null) "rem-$blockId" else null,
        reminderZoneId = "UTC",
        reminderLocalDate = reminderLocalDate,
        reminderLocalTime = reminderLocalTime,
        triggerInstantMs = triggerInstantMs,
        policy = ReminderPolicy.FOLLOW_LOCAL,
        reminderStatus = reminderStatus,
        recurrence = recurrence,
        snoozedUntilMs = snoozedUntilMs,
        missedSummarized = false,
    )

    @Test
    fun pinyinTableCoversGb2312CommonCharsAndMixedText() {
        for (ch in listOf('着', '的', '浮', '记', '录', '整', '理')) {
            val syl = ChinesePinyinTable.pinyinOf(ch)
            assertNotNull("Missing pinyin for $ch", syl)
            assertTrue("Expected lowercase alpha for $ch, got $syl", syl!!.all { it in 'a'..'z' })
        }
        assertEquals("nv", ChinesePinyinTable.pinyinOf('女'))
        assertEquals("lv", ChinesePinyinTable.pinyinOf('绿'))

        val pure = ChinesePinyinTable.toPinyinParts("读书笔记")
        assertEquals("dushubiji", pure.full)
        assertEquals("dsbj", pure.initials)
        assertEquals(listOf("du", "shu", "bi", "ji"), pure.syllables)

        val mixed = ChinesePinyinTable.toPinyinParts("UI设计 · v2复盘!")
        assertEquals("uishejiv2fupan", mixed.full)
        assertEquals("usjvfp", mixed.initials)
        assertEquals(listOf("ui", "she", "ji", "v2", "fu", "pan"), mixed.syllables)

        val empty = ChinesePinyinTable.toPinyinParts("   ---  ")
        assertEquals("", empty.full)
        assertEquals("", empty.initials)
        assertTrue(empty.syllables.isEmpty())
    }

    @Test
    fun pinyinMatcherSupportsSyllableAlignedFullAndInitialsWithoutMidSyllableNoise() {
        assertTrue(LocalFuzzyMatcher.isAsciiPinyinQuery("du shu"))
        assertTrue(LocalFuzzyMatcher.isAsciiPinyinQuery("dsbj"))
        assertFalse(LocalFuzzyMatcher.isAsciiPinyinQuery("a"))
        assertFalse(LocalFuzzyMatcher.isAsciiPinyinQuery("1234"))
        assertFalse(LocalFuzzyMatcher.isAsciiPinyinQuery("读书"))
        assertFalse(LocalFuzzyMatcher.isAsciiPinyinQuery("a".repeat(25)))

        val fullSpaced = LocalFuzzyMatcher.matchPinyin("du shu", "读书笔记")
        assertTrue(fullSpaced.matched)
        assertEquals(FuzzyMatchKind.PINYIN_FULL, fullSpaced.kind)
        assertTrue(fullSpaced.score in 0.85..0.95)
        assertEquals("读书", fullSpaced.matchedSnippet)

        val fullSub = LocalFuzzyMatcher.matchPinyin("biji", "读书笔记")
        assertTrue(fullSub.matched)
        assertEquals(FuzzyMatchKind.PINYIN_FULL, fullSub.kind)
        assertEquals("笔记", fullSub.matchedSnippet)

        val midSyllableBogus = LocalFuzzyMatcher.matchPinyin("an", "成长")
        assertFalse(midSyllableBogus.matched)
        assertEquals(FuzzyMatchKind.NONE, midSyllableBogus.kind)

        val initials = LocalFuzzyMatcher.matchPinyin("dsbj", "读书笔记")
        assertTrue(initials.matched)
        assertEquals(FuzzyMatchKind.PINYIN_INITIALS, initials.kind)
        assertTrue(initials.score in 0.72..0.85)
        assertEquals("读书笔记", initials.matchedSnippet)

        val fp = LocalFuzzyMatcher.matchPinyin("fp", "本周复盘总结")
        assertTrue(fp.matched)
        assertEquals(FuzzyMatchKind.PINYIN_INITIALS, fp.kind)
        assertEquals("复盘", fp.matchedSnippet)

        assertTrue(LocalFuzzyMatcher.matchesTagOrNoteQuery("gz", "工作"))
        assertTrue(LocalFuzzyMatcher.matchesTagOrNoteQuery("gongzuo", "工作"))
        assertTrue(LocalFuzzyMatcher.matchesTagOrNoteQuery("Work", "WorkLog"))
        assertFalse(LocalFuzzyMatcher.matchesTagOrNoteQuery("xy", "工作"))
    }

    @Test
    fun fuzzyMatcherHandlesEditDistanceTyposAndRejectsUnrelatedQueries() {
        assertEquals(0, LocalFuzzyMatcher.damerauLevenshtein("abc", "abc", maxDist = 2))
        assertEquals(1, LocalFuzzyMatcher.damerauLevenshtein("flaot", "float", maxDist = 2))
        assertEquals(1, LocalFuzzyMatcher.damerauLevenshtein("读书记笔", "读书笔记", maxDist = 2))
        assertEquals(3, LocalFuzzyMatcher.damerauLevenshtein("abcdef", "uvwxyz", maxDist = 2))

        val exact = LocalFuzzyMatcher.matchFuzzyText("Floating", "floating notes app")
        assertTrue(exact.matched)
        assertEquals(FuzzyMatchKind.EXACT, exact.kind)
        assertEquals(1.0, exact.score, 0.0001)

        val asciiTypo = LocalFuzzyMatcher.matchFuzzyText("floting", "My Floating Notes")
        assertTrue(asciiTypo.matched)
        assertEquals(FuzzyMatchKind.EDIT_DISTANCE, asciiTypo.kind)
        assertEquals("Floating", asciiTypo.matchedSnippet)

        val cjkTransposition = LocalFuzzyMatcher.matchFuzzyText("读书记笔", "今日读书笔记整理")
        assertTrue(cjkTransposition.matched)
        assertEquals(FuzzyMatchKind.EDIT_DISTANCE, cjkTransposition.kind)

        val twoCharWithOverlap = LocalFuzzyMatcher.matchFuzzyText("笔录", "我的笔记")
        assertTrue(twoCharWithOverlap.matched)
        assertEquals(FuzzyMatchKind.EDIT_DISTANCE, twoCharWithOverlap.kind)

        val twoCharWithoutOverlap = LocalFuzzyMatcher.matchFuzzyText("笔录", "开发计划")
        assertFalse(twoCharWithoutOverlap.matched)
        assertEquals(FuzzyMatchKind.NONE, twoCharWithoutOverlap.kind)

        val unrelatedLong = LocalFuzzyMatcher.matchFuzzyText(
            "完全不存在外星量子信号",
            "读书笔记整理与周报复盘，Floating Notes 项目推进记录",
        )
        assertFalse(unrelatedLong.matched)
        assertEquals(FuzzyMatchKind.NONE, unrelatedLong.kind)

        val unrelatedMixed = LocalFuzzyMatcher.matchFuzzyText(
            "合成查找1a2b3c4d",
            "读书笔记整理与周报复盘，Floating Notes 项目推进记录",
        )
        assertFalse(unrelatedMixed.matched)
        assertEquals(FuzzyMatchKind.NONE, unrelatedMixed.kind)
    }

    @Test
    fun urgencyEngineComputesDeterministicTiersScoresAndBadges() {
        val today = LocalDate.of(2026, 11, 14)

        val overdue2 = TaskUrgencyEngine.evaluateRaw(
            text = "整理周报",
            dueDate = "2026-11-12",
            updatedAtIso = "2026-11-10T08:00:00Z",
            hasActiveReminder = false,
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.CRITICAL, overdue2.tier)
        assertEquals(92, overdue2.score)
        assertEquals("逾期 2 天", overdue2.badgeLabel)

        val overdue10Cap = TaskUrgencyEngine.evaluateRaw(
            text = "紧急报销",
            dueDate = "2026-11-04",
            updatedAtIso = "2026-11-01T08:00:00Z",
            hasActiveReminder = true,
            reminderMissed = true,
            isSnoozed = true,
            isRecurring = true,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        // Base 80 + cap 40 + missed 15 + active(d<=0) 10 + snoozed 6 + recurring 4 + keyword 12 = 167
        assertEquals(167, overdue10Cap.score)
        assertEquals(TaskUrgencyTier.CRITICAL, overdue10Cap.tier)
        assertEquals("逾期 10 天", overdue10Cap.badgeLabel)

        val dueTodayUrgent = TaskUrgencyEngine.evaluateRaw(
            text = "提交 DDL 材料",
            dueDate = "2026-11-14",
            updatedAtIso = null,
            hasActiveReminder = true,
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.HIGH, dueTodayUrgent.tier)
        assertEquals(94, dueTodayUrgent.score) // 72 + 10 + 12
        assertEquals("今日紧急", dueTodayUrgent.badgeLabel)

        val dueTodayPlain = TaskUrgencyEngine.evaluateRaw(
            text = "买牛奶",
            dueDate = "2026-11-14",
            updatedAtIso = null,
            hasActiveReminder = false,
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.HIGH, dueTodayPlain.tier)
        assertEquals(72, dueTodayPlain.score)
        assertNull(dueTodayPlain.badgeLabel)

        val dueTomorrow = TaskUrgencyEngine.evaluateRaw(
            text = "开会",
            dueDate = "2026-11-15",
            updatedAtIso = null,
            hasActiveReminder = true, // d > 0 should NOT add +10
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.MEDIUM, dueTomorrow.tier)
        assertEquals(58, dueTomorrow.score)
        assertEquals("明日截止", dueTomorrow.badgeLabel)

        val dueIn5DaysImportant = TaskUrgencyEngine.evaluateRaw(
            text = "办理续费手续",
            dueDate = "2026-11-19",
            updatedAtIso = null,
            hasActiveReminder = false,
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.MEDIUM, dueIn5DaysImportant.tier)
        assertEquals(44, dueIn5DaysImportant.score) // 32 + 12
        assertEquals("重要", dueIn5DaysImportant.badgeLabel)

        val staleTask = TaskUrgencyEngine.evaluateRaw(
            text = "旧想法梳理",
            dueDate = null,
            updatedAtIso = "2026-10-25T08:00:00Z", // 20 days stale
            hasActiveReminder = false,
            reminderMissed = false,
            isSnoozed = false,
            isRecurring = false,
            today = today,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(TaskUrgencyTier.STALE, staleTask.tier)
        assertEquals(34, staleTask.score) // 28 + (20 - 14)
        assertEquals("搁置 20 天", staleTask.badgeLabel)
    }

    @Test
    fun sortByUrgencyOrdersByScoreDueDateTimeAndUpdatedAt() {
        val today = LocalDate.of(2026, 11, 14)

        val tOverdue = taskItem("b1", "普通任务", dueDate = "2026-11-13")
        val tTodayLate = taskItem(
            "b2",
            "下午联系",
            dueDate = "2026-11-14",
            reminderLocalTime = "16:00",
            noteUpdatedAt = "2026-11-14T09:00:00Z",
        )
        val tTodayEarlyOld = taskItem(
            "b3",
            "上午联系A",
            dueDate = "2026-11-14",
            reminderLocalTime = "09:00",
            noteUpdatedAt = "2026-11-14T07:00:00Z",
        )
        val tTodayEarlyNew = taskItem(
            "b4",
            "上午联系B",
            dueDate = "2026-11-14",
            reminderLocalTime = "09:00",
            noteUpdatedAt = "2026-11-14T10:00:00Z",
        )
        val tReminderOnlyToday = taskItem(
            "b5",
            "仅提醒日期",
            dueDate = null,
            reminderLocalDate = "2026-11-14",
            reminderLocalTime = "08:00",
        )
        val tUnscheduled = taskItem("b6", "未排期", dueDate = null)

        val sorted = TaskUrgencyEngine.sortByUrgency(
            items = listOf(tUnscheduled, tTodayLate, tTodayEarlyOld, tOverdue, tReminderOnlyToday, tTodayEarlyNew),
            today = today,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            listOf("b1", "b4", "b3", "b2", "b5", "b6"),
            sorted.map { it.blockId },
        )
    }
}
