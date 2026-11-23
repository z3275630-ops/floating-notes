package io.github.z3275630ops.floatingnotes.data.discovery

import java.util.Locale
import kotlin.math.abs

internal enum class FuzzyMatchKind {
    NONE,
    EXACT,
    PINYIN_FULL,
    PINYIN_INITIALS,
    EDIT_DISTANCE,
}

internal data class FuzzyMatchResult(
    val matched: Boolean,
    val kind: FuzzyMatchKind,
    val score: Double,
    val matchedSnippet: String?,
)

internal object LocalFuzzyMatcher {
    private val NO_MATCH = FuzzyMatchResult(
        matched = false,
        kind = FuzzyMatchKind.NONE,
        score = 0.0,
        matchedSnippet = null,
    )

    private val ASCII_WORD_REGEX = Regex("[A-Za-z0-9]{4,}")
    private const val TARGET_WINDOW_CHAR_LIMIT = 600
    private const val MIN_EDIT_SIMILARITY = 0.72

    fun isAsciiPinyinQuery(query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.length !in 2..24) return false
        var letterCount = 0
        for (ch in trimmed) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> letterCount++
                ch.isWhitespace() -> Unit
                else -> return false
            }
        }
        return letterCount >= 2
    }

    fun matchPinyin(query: String, targetText: String): FuzzyMatchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || targetText.isEmpty()) return NO_MATCH

        val hasWhitespace = trimmed.any { it.isWhitespace() }
        val normalizedQuery = buildString(trimmed.length) {
            for (ch in trimmed) {
                when {
                    ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                    ch in 'A'..'Z' -> append((ch.code + 32).toChar())
                    ch.isWhitespace() -> Unit
                    else -> return NO_MATCH
                }
            }
        }
        if (normalizedQuery.length < 2) return NO_MATCH

        val indexed = ChinesePinyinTable.indexSyllables(targetText)
        if (indexed.isEmpty()) return NO_MATCH

        // 1. Syllable-aligned full pinyin match (start and end on syllable boundaries).
        var bestFullScore = -1.0
        var bestFullSnippet: String? = null
        for (startIdx in indexed.indices) {
            var matchedLen = 0
            var endIdx = startIdx
            var possible = true
            while (endIdx < indexed.size && matchedLen < normalizedQuery.length) {
                val syl = indexed[endIdx].syllable
                if (!normalizedQuery.startsWith(syl, startIndex = matchedLen)) {
                    possible = false
                    break
                }
                matchedLen += syl.length
                endIdx++
            }
            if (possible && matchedLen == normalizedQuery.length) {
                val matchedCount = endIdx - startIdx
                val coverage = matchedCount.toDouble() / indexed.size
                val score = if (matchedCount == indexed.size) {
                    0.95
                } else {
                    (0.85 + 0.08 * coverage).coerceIn(0.85, 0.94)
                }
                if (score > bestFullScore) {
                    bestFullScore = score
                    val sliceStart = indexed[startIdx].startOffset
                    val sliceEnd = indexed[endIdx - 1].endOffset
                    bestFullSnippet = targetText.substring(sliceStart, sliceEnd)
                }
            }
        }
        if (bestFullScore >= 0.0) {
            return FuzzyMatchResult(
                matched = true,
                kind = FuzzyMatchKind.PINYIN_FULL,
                score = bestFullScore,
                matchedSnippet = bestFullSnippet,
            )
        }

        // 2. Initials match (require >= 2 chars, no spaces in query).
        if (!hasWhitespace && normalizedQuery.length >= 2) {
            val initials = buildString(indexed.size) {
                for (item in indexed) append(item.syllable[0])
            }
            val hitIdx = initials.indexOf(normalizedQuery)
            if (hitIdx >= 0) {
                val coverage = normalizedQuery.length.toDouble() / initials.length.coerceAtLeast(1)
                val score = (0.72 + 0.13 * coverage).coerceIn(0.72, 0.85)
                val sliceStart = indexed[hitIdx].startOffset
                val sliceEnd = indexed[hitIdx + normalizedQuery.length - 1].endOffset
                return FuzzyMatchResult(
                    matched = true,
                    kind = FuzzyMatchKind.PINYIN_INITIALS,
                    score = score,
                    matchedSnippet = targetText.substring(sliceStart, sliceEnd),
                )
            }
        }

        return NO_MATCH
    }

    fun damerauLevenshtein(a: String, b: String, maxDist: Int = 2): Int {
        if (a == b) return 0
        if (maxDist <= 0) return maxDist + 1
        val m = a.length
        val n = b.length
        if (abs(m - n) > maxDist) return maxDist + 1
        if (m == 0) return n
        if (n == 0) return m

        var prevPrev = IntArray(n + 1)
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            var rowMin = i
            val aChar = a[i - 1]
            for (j in 1..n) {
                val bChar = b[j - 1]
                val subCost = if (aChar == bChar) 0 else 1
                var best = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + subCost,
                )
                if (i > 1 && j > 1 && aChar == b[j - 2] && a[i - 2] == bChar) {
                    best = minOf(best, prevPrev[j - 2] + 1)
                }
                curr[j] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > maxDist) return maxDist + 1
            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }

        val dist = prev[n]
        return if (dist <= maxDist) dist else maxDist + 1
    }

    fun matchFuzzyText(query: String, targetText: String): FuzzyMatchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || targetText.isEmpty()) return NO_MATCH

        // 1. Exact case-insensitive substring match.
        if (targetText.contains(trimmed, ignoreCase = true)) {
            return FuzzyMatchResult(
                matched = true,
                kind = FuzzyMatchKind.EXACT,
                score = 1.0,
                matchedSnippet = null,
            )
        }

        // 2. Pinyin full / initials match for ASCII pinyin queries.
        if (isAsciiPinyinQuery(trimmed)) {
            val pinyinResult = matchPinyin(trimmed, targetText)
            if (pinyinResult.matched) return pinyinResult
        }

        // 3. Bounded edit distance (operate on original-string indices to avoid Unicode case-expansion out-of-bounds).
        val compactQuery = trimmed.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
        val hasCjkInQuery = compactQuery.any { isCjk(it) }
        val boundedTarget = targetText.take(TARGET_WINDOW_CHAR_LIMIT)

        if (hasCjkInQuery && compactQuery.length in 2..10) {
            val cjkWindowMatch = matchCjkSlidingWindow(compactQuery, boundedTarget)
            if (cjkWindowMatch.matched) return cjkWindowMatch
        }

        // ASCII word token edit distance (only when query has no unmatched CJK characters).
        if (!hasCjkInQuery) {
            val asciiTokenMatch = matchAsciiTokens(trimmed.lowercase(Locale.ROOT), boundedTarget)
            if (asciiTokenMatch.matched) return asciiTokenMatch
        }

        return NO_MATCH
    }

    fun matchesTagOrNoteQuery(query: String, label: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || label.isEmpty()) return false
        if (label.contains(trimmed, ignoreCase = true)) return true
        return isAsciiPinyinQuery(trimmed) && matchPinyin(trimmed, label).matched
    }

    private fun matchCjkSlidingWindow(
        compactQuery: String,
        targetOriginal: String,
    ): FuzzyMatchResult {
        val qLen = compactQuery.length
        val maxAllowedDist = when {
            qLen <= 5 -> 1
            else -> 2
        }
        val minWin = (qLen - 1).coerceAtLeast(2)
        val maxWin = qLen + 1

        var bestScore = -1.0
        var bestSnippet: String? = null

        for (winLen in minWin..maxWin) {
            if (winLen > targetOriginal.length) continue
            for (start in 0..(targetOriginal.length - winLen)) {
                val rawWindow = targetOriginal.substring(start, start + winLen)
                if (rawWindow.first().isWhitespace() || rawWindow.last().isWhitespace()) continue
                val window = rawWindow.lowercase(Locale.ROOT)
                if (qLen == 2 && !hasDirectCharOverlap(compactQuery, window)) continue

                val dist = damerauLevenshtein(compactQuery, window, maxAllowedDist)
                if (dist > maxAllowedDist) continue

                val maxLen = maxOf(qLen, window.length)
                val sim = 1.0 - dist.toDouble() / maxLen
                if (qLen >= 6 && sim < MIN_EDIT_SIMILARITY) continue

                val score = (0.55 + 0.25 * sim).coerceIn(0.50, 0.80)
                if (score > bestScore) {
                    bestScore = score
                    bestSnippet = rawWindow
                }
            }
        }

        return if (bestScore >= 0.0) {
            FuzzyMatchResult(
                matched = true,
                kind = FuzzyMatchKind.EDIT_DISTANCE,
                score = bestScore,
                matchedSnippet = bestSnippet,
            )
        } else {
            NO_MATCH
        }
    }

    private fun matchAsciiTokens(
        queryLower: String,
        targetOriginal: String,
    ): FuzzyMatchResult {
        val queryTokens = ASCII_WORD_REGEX.findAll(queryLower).map { it.value }.toList()
        if (queryTokens.isEmpty()) return NO_MATCH
        val targetTokens = ASCII_WORD_REGEX.findAll(targetOriginal).toList()
        if (targetTokens.isEmpty()) return NO_MATCH

        var simSum = 0.0
        var bestSnippet: String? = null

        for (qTok in queryTokens) {
            val maxDist = if (qTok.length >= 6) 2 else 1
            var tokenBestSim = -1.0
            var tokenSnippet: String? = null

            for (tMatch in targetTokens) {
                val rawTok = tMatch.value
                val tTok = rawTok.lowercase(Locale.ROOT)
                val dist = damerauLevenshtein(qTok, tTok, maxDist)
                if (dist > maxDist) continue
                val maxLen = maxOf(qTok.length, tTok.length)
                val sim = 1.0 - dist.toDouble() / maxLen
                if (sim >= MIN_EDIT_SIMILARITY && sim > tokenBestSim) {
                    tokenBestSim = sim
                    tokenSnippet = rawTok
                }
            }

            if (tokenBestSim < 0.0) return NO_MATCH
            simSum += tokenBestSim
            if (bestSnippet == null) bestSnippet = tokenSnippet
        }

        val avgSim = simSum / queryTokens.size
        val score = (0.55 + 0.25 * avgSim).coerceIn(0.50, 0.80)
        return FuzzyMatchResult(
            matched = true,
            kind = FuzzyMatchKind.EDIT_DISTANCE,
            score = score,
            matchedSnippet = bestSnippet,
        )
    }

    private fun hasDirectCharOverlap(a: String, b: String): Boolean {
        for (ch in a) {
            if (b.indexOf(ch) >= 0) return true
        }
        return false
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }
}
