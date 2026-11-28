package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

/** 0.1.41 (docs/online-design.md 4.8, AP6, AP12; plan AP-6): the arrival record: answers only, 90 days, gone with the switch. */
class AskedStoreTest {
    private val d = LocalDate.of(2026, 11, 28)

    @Test
    fun theRecordsRules(@TempDir dir: File) {
        val m = mapOf("n1" to d)
        assertTrue(AskedRecords.recent(m, "n1", d.plusDays(89)), "D+89")
        assertFalse(AskedRecords.recent(m, "n1", d.plusDays(90)), "D+90")
        assertFalse(AskedRecords.recent(m, "n2", d))
        assertEquals(mapOf("n1" to d), AskedRecords.prune(mapOf("n1" to d, "n2" to d.minusDays(90), "n3" to d.plusDays(2)), d),
            "90 days old and 2 days ahead (a clock set back) go")
        assertEquals(mapOf("n4" to d.plusDays(1)), AskedRecords.prune(mapOf("n4" to d.plusDays(1)), d), "one day ahead stays")
        val many = (1..1_005).associate { "n$it" to d.minusDays((it % 80).toLong()) }
        val kept = AskedRecords.prune(many, d)
        assertEquals(AskedRecords.MAX, kept.size)
        assertTrue(kept.values.min() >= many.values.sortedDescending()[AskedRecords.MAX - 1], "the newest kept")
        assertEquals(emptyMap<String, LocalDate>(), AskedRecords.parse("""{"format":"other/1","asked":{"n1":"2026-11-28"}}"""))
        assertEquals(emptyMap<String, LocalDate>(), AskedRecords.parse("""{"format":"beamline-asked/1","asked":{"Main St":"2026-11-28","n1":"soon","n2":5}}"""))
        assertEquals(emptyMap<String, LocalDate>(), AskedRecords.parse("{broken"))
        assertEquals(mapOf("w5" to d), AskedRecords.parse("""{"format":"beamline-asked/1","asked":{"w5":"2026-11-28"," n6":"2026-11-28"}}"""))
        assertEquals("""{"format":"beamline-asked/1","asked":{"n1":"2026-11-28"}}""", AskedRecords.format(mapOf("n1" to d)))
        val store = AskedStore(File(dir, AskedStore.FILE_NAME), d)
        store.markAnswered("n1", d)
        assertTrue(AskedStore(File(dir, AskedStore.FILE_NAME), d).recent("n1", d.plusDays(10)), "kept across a restart")
        assertEquals("AskedStore", store.toString(), "prints nothing of it (PV2)")
        File(dir, AskedStore.FILE_NAME).writeText("[1,")
        assertFalse(AskedStore(File(dir, AskedStore.FILE_NAME), d).recent("n1", d), "an unreadable file reads as empty")
    }

    /** Plan AP-6: a question shown and not answered is remembered in memory only; an answer is written, and only its id and day. */
    @Test
    fun onlyAnAnswerIsWritten(@TempDir dir: File) {
        val f = File(dir, AskedStore.FILE_NAME)
        val store = AskedStore(f, d)
        store.noteShown("n77")
        assertTrue(store.recent("n77", d), "not asked again in this run")
        assertFalse(f.exists(), "nothing on disk for a question only shown")
        assertFalse(AskedStore(f, d).recent("n77", d), "after the app is closed it may come again")
        store.markAnswered("n88", d)
        assertEquals("""{"format":"beamline-asked/1","asked":{"n88":"2026-11-28"}}""", f.readText(), "only the answered place and its day")
        assertTrue(AskedStore(f, d).recent("n88", d.plusDays(89)))
        assertFalse(AskedStore(f, d).recent("n88", d.plusDays(90)))
        // an old answer is dropped at the next write
        store.markAnswered("n99", d.plusDays(100))
        assertEquals("""{"format":"beamline-asked/1","asked":{"n99":"2027-03-08"}}""", f.readText())
    }

    /** The fixer's review (AP12: "at most 90 days old"): an old answer leaves the disk when the store is read, with no new answer. */
    @Test
    fun anOldAnswerIsGoneWhenTheStoreIsRead(@TempDir dir: File) {
        val f = File(dir, AskedStore.FILE_NAME)
        val both = """{"format":"beamline-asked/1","asked":{"n1":"${d.minusDays(95)}","n2":"${d.minusDays(10)}"}}"""
        f.writeText(both)
        // opened 10 days ago (the app's start), when both were in force; read today: the 95-day-old answer goes
        val opened = AskedStore(f, d.minusDays(10))
        assertEquals(both, f.readText())
        assertFalse(opened.recent("n3", d))
        assertEquals("""{"format":"beamline-asked/1","asked":{"n2":"${d.minusDays(10)}"}}""", f.readText(), "the 95-day-old answer is gone")
        // opened today: gone at once, with nothing read or answered
        f.writeText(both)
        AskedStore(f, d)
        assertEquals("""{"format":"beamline-asked/1","asked":{"n2":"${d.minusDays(10)}"}}""", f.readText())
        f.writeText("""{"format":"beamline-asked/1","asked":{"n1":"${d.minusDays(100)}"}}""")
        AskedStore(f, d)
        assertFalse(f.exists(), "every answer old: the file goes")
        assertEquals(emptyList<String>(), dir.list()!!.toList(), "no file left behind")
    }

    /** Plan AP-6: Contribute or online features off: the file and the memory are gone. */
    @Test
    fun clearingTheSwitchDeletesIt(@TempDir dir: File) {
        val f = File(dir, AskedStore.FILE_NAME)
        val store = AskedStore(f, d)
        store.markAnswered("n1", d)
        store.noteShown("n2")
        assertTrue(f.exists())
        store.clear()
        assertFalse(f.exists())
        assertFalse(store.recent("n1", d))
        assertFalse(store.recent("n2", d))
        assertEquals(emptyList<String>(), dir.list()!!.toList(), "no file left behind")
        store.clear()   // again: nothing to do
        store.markAnswered("n3", d)
        assertTrue(AskedStore(f, d).recent("n3", d), "written again after the switch came back")
    }
}
