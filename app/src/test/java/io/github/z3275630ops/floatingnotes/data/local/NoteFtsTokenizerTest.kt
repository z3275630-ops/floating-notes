package io.github.z3275630ops.floatingnotes.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFtsTokenizerTest {

    @Test
    fun buildIndexTextExtractsCjkUnigramsBigramsAndLowercaseAsciiInOrder() {
        assertEquals(
            "讨 论 沟 通 讨论 论沟 沟通",
            NoteFtsTokenizer.buildIndexText(title = null, searchText = "讨论沟通"),
        )
        assertEquals(
            "讨 论 沟 通 讨论 论沟 沟通 作 业 作业 android 100",
            NoteFtsTokenizer.buildIndexText(
                title = null,
                searchText = "讨论沟通、作业😀 Android 100%_!",
            ),
        )
    }

    @Test
    fun buildIndexTextDeduplicatesAndIsolatesTitleBoundary() {
        // Repeated characters/words remain unique in order of appearance.
        assertEquals(
            "讨 论 讨论 论讨",
            NoteFtsTokenizer.buildIndexText(title = null, searchText = "讨论讨论"),
        )
        // Title and searchText must not join into a cross-field bigram like "乙丙".
        assertEquals(
            "甲 乙 甲乙 丙 丁 丙丁",
            NoteFtsTokenizer.buildIndexText(title = "甲乙", searchText = "丙丁"),
        )
    }

    @Test
    fun buildIndexTextSupportsCjkExtensionAAndCompatibilityBlocks() {
        val indexed = NoteFtsTokenizer.buildIndexText(title = null, searchText = "\u3400\uF900")
        assertEquals("\u3400 \uF900 \u3400\uF900", indexed)
    }

    @Test
    fun buildFtsMatchQueryBuildsCjkBigramsAndAsciiPrefixes() {
        assertEquals("讨论 论沟 沟通", NoteFtsTokenizer.buildFtsMatchQuery("讨论沟通"))
        assertEquals("沟通", NoteFtsTokenizer.buildFtsMatchQuery("沟通"))
        assertEquals("沟", NoteFtsTokenizer.buildFtsMatchQuery("沟"))
        assertEquals("android*", NoteFtsTokenizer.buildFtsMatchQuery("Android"))
        assertEquals("android* 100*", NoteFtsTokenizer.buildFtsMatchQuery("android 100%_!"))
        assertEquals("作业", NoteFtsTokenizer.buildFtsMatchQuery("、作业😀"))
        assertEquals("android*", NoteFtsTokenizer.buildFtsMatchQuery("a android"))
    }

    @Test
    fun buildFtsMatchQueryReturnsNullWhenNoEffectiveTokensRemain() {
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery(""))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("   \t\n "))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("%_"))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("😀🎉"))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("a"))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("1"))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("a b c"))
    }

    @Test
    fun buildFtsMatchQuerySanitizesQuotesOperatorsAndEnforcesTwelveTermCap() {
        assertEquals("讨论 沟通", NoteFtsTokenizer.buildFtsMatchQuery("讨论 \"沟通\""))
        assertEquals("android* test*", NoteFtsTokenizer.buildFtsMatchQuery("\"android\" \"test\""))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("\"\"\""))
        assertNull(NoteFtsTokenizer.buildFtsMatchQuery("a\"b"))
        assertEquals(
            "and* or* not* near*",
            NoteFtsTokenizer.buildFtsMatchQuery("AND OR NOT NEAR"),
        )

        val longCjk = "一二三四五六七八九十甲乙丙丁戊己庚辛"
        val cjkQuery = NoteFtsTokenizer.buildFtsMatchQuery(longCjk)!!
        assertEquals(12, cjkQuery.split(' ').size)
        assertTrue(cjkQuery.startsWith("一二 二三"))

        val manyAscii = (1..20).joinToString(" ") { "kw$it" }
        val asciiQuery = NoteFtsTokenizer.buildFtsMatchQuery(manyAscii)!!
        assertEquals(12, asciiQuery.split(' ').size)
        assertTrue(asciiQuery.endsWith("kw12*"))

        val repeatedFirst = "沟通 ".repeat(20) + "计划"
        assertEquals("沟通 计划", NoteFtsTokenizer.buildFtsMatchQuery(repeatedFirst))
    }

    @Test
    fun toEntityMapsNoteIdAndIndexedText() {
        val note = NoteEntity(
            id = "n-42",
            title = "周报",
            documentJson = "{}",
            searchText = "Android 复盘",
            sourceType = "manual",
            sourceUrl = null,
            createdAt = "2026-11-25T00:00:00.000Z",
            updatedAt = "2026-11-25T00:00:00.000Z",
            revision = 1L,
            contentHash = "h",
            lastDraftId = "d",
            committedSeq = 1L,
        )
        val entity = NoteFtsTokenizer.toEntity(note)
        assertEquals("n-42", entity.noteId)
        assertEquals("周 报 周报 android 复 盘 复盘", entity.indexedText)
    }
}
