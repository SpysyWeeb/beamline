package io.github.spysyweeb.beamline.search

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Rule S5: the input cap comes from ranking.json and counts code points, as Python's text[:n]. No pack needed. */
class QueryCapTest {
    @Test
    fun capComesFromRankingJson() {
        assertEquals(256, Ranking.load().maxQueryChars)
        val src = Json.parseToJsonElement(File(TestEnv.repoRoot, "packbuilder/search/ranking.json").readText())
        assertEquals(256, src.jsonObject["search"]!!.jsonObject["max_query_chars"]!!.jsonPrimitive.int)
    }

    @Test
    fun cutCountsCodePoints() {
        val cap = { t: String, n: Int -> SearchEngine.capCodePoints(t, n) }
        val short = "sheetz"
        assertSame(short, cap(short, 256))
        assertEquals("ab", cap("abc", 2))
        assertEquals("", cap("abc", 0))
        // an emoji is one code point (two UTF-16 units), never split
        val e = "😀"
        assertEquals("a".repeat(255) + e, cap("a".repeat(255) + e + "bbb", 256))
        assertEquals("a".repeat(255), cap("a".repeat(255) + e + "bbb", 255))
        assertEquals(e.repeat(256), cap(e.repeat(300), 256))
        assertEquals(512, cap(e.repeat(300), 256).length)
        // 256 emoji: 512 chars but 256 code points, kept whole
        val exact = e.repeat(256)
        assertSame(exact, cap(exact, 256))
        // a combining mark is its own code point (Python cuts the same way)
        assertEquals("ée", cap("éé", 3))
        // an unpaired surrogate counts as one code point, as in Python
        assertEquals("a\uD800b", cap("a\uD800bc", 3))
    }
}
