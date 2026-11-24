package io.github.spysyweeb.beamline.feature.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class AboutTextsTest {

    private val architecture = File(System.getProperty("beamline.repoRoot") ?: "../../..", "docs/architecture.md")

    /** The first-run notice is the text of docs/architecture.md 5.1, approved by the owner as written on 2026-11-16. */
    @Test
    fun theFirstRunNoticeIsTheContractsText() {
        val doc = architecture.readText().replace("\r\n", "\n")
        val start = doc.indexOf("First-run notice (A; approved by the owner as written on 2026-11-16, no longer a draft): \"")
        check(start >= 0) { "the approved notice is no longer in docs/architecture.md" }
        val rest = doc.substring(start).substringAfter('"')
        val draft = rest.substringBefore('"').replace('\n', ' ')
        assertEquals(draft, AboutTexts.FIRST_RUN_NOTICE)
        assertEquals("[${AboutTexts.FIRST_RUN_ACCEPT}]", rest.substringAfter('"').trim().lines().first())
        assertEquals(1, FIRST_RUN_NOTICE_VERSION)
    }

    @Test
    fun licenceTextsAreReflowedIntoParagraphs() {
        // Wrapped prose (lines of 60+ characters) is joined; blank lines end a paragraph.
        val text = "Permission is hereby granted, free of charge, to any person obtaining\n" +
            "   a copy of this software.\n\n\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS\r\n" +
            "OR IMPLIED.\n  \n"
        assertEquals(
            listOf(
                "Permission is hereby granted, free of charge, to any person obtaining a copy of this software.",
                "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.",
            ),
            reflow(text),
        )
        assertEquals(emptyList<String>(), reflow("\n\n"))
    }

    @Test
    fun shortLinesAndRulesKeepTheNoticesListReadable() {
        val text = "=====\nMap data\n=====\n\nOpenStreetMap data\n  Licence: ODbL-1.0\n  © OpenStreetMap contributors\n\n---\nMIT\n---\n"
        assertEquals(
            listOf("Map data", "OpenStreetMap data\nLicence: ODbL-1.0\n© OpenStreetMap contributors", "MIT"),
            reflow(text),
        )
    }
}
