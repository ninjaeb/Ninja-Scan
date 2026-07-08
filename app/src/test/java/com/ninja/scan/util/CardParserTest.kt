package com.ninja.scan.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardParserTest {

    /** Builds a single-line OcrLine spanning the given horizontal range. */
    private fun line(text: String, top: Int, left: Int = 0, right: Int = 100, bottom: Int = top + 20) =
        OcrLine(text, left, top, right, bottom)

    @Test
    fun `name and job title printed as side-by-side columns are kept separate`() {
        // Same physical row: "Wong Mee Yoong" on the left, "HWG Advisory" on
        // the right, with a wide gap between them (as ML Kit would report
        // two elements of one merged line on a two-column card layout).
        val lines = listOf(
            line("No.29, Jalan SS 21/37, Damansara Utama,", top = 0),
            line("Wong Mee Yoong", top = 40, left = 0, right = 150),
            line("HWG Advisory", top = 40, left = 400, right = 550),
            line("+6010-276 1341", top = 80),
            line("mycylvr@gmail.com", top = 100),
        )

        val card = CardParser.parse(lines)

        assertEquals("Wong Mee Yoong", card.name)
        assertEquals("HWG Advisory", card.jobTitle)
    }

    @Test
    fun `website is not confused with the email's domain`() {
        val lines = listOf(
            line("Some Person", top = 0),
            line("mycylvr@gmail.com", top = 20),
            line("www.hwg.asia", top = 40),
        )

        val card = CardParser.parse(lines)

        assertEquals("mycylvr@gmail.com", card.email)
        assertEquals("www.hwg.asia", card.website)
    }

    @Test
    fun `address-like line is never chosen as name`() {
        // Only an address (digit-bearing) and a job-title-hinting line are
        // available — no plausible name candidate should be picked, unlike
        // the old fallback which accepted any digit-bearing line.
        val lines = listOf(
            line("No.29, Jalan SS 21/37, Damansara Utama,", top = 0),
            line("47400 Petaling Jaya, Selangor, Malaysia.", top = 20),
            line("Marketing Advisor", top = 40),
        )

        val card = CardParser.parse(lines)

        assertNotEquals("No.29, Jalan SS 21/37, Damansara Utama,", card.name)
        assertTrue(card.name.isEmpty())
    }

    @Test
    fun `flat text compatibility wrapper resolves fields in original order`() {
        val text = """
            Jane Smith
            Senior Consultant
            Acme Solutions Ltd
            +1 555 123 4567
            jane.smith@acme.com
            www.acme.com
        """.trimIndent()

        val card = CardParser.parse(text)

        assertEquals("Jane Smith", card.name)
        assertEquals("Senior Consultant", card.jobTitle)
        assertEquals("jane.smith@acme.com", card.email)
        assertEquals("www.acme.com", card.website)
    }

    @Test
    fun `stacked name and title beat a taller unrelated logo line`() {
        val lines = listOf(
            line("SiGNMASTR.", top = 0, left = 0, right = 300, bottom = 80),
            line("Wilson Choo", top = 150, left = 0, right = 180, bottom = 170),
            line("Chief Operating Officer", top = 175, left = 0, right = 280, bottom = 195),
        )
        val card = CardParser.parse(lines)
        assertEquals("Wilson Choo", card.name)
        assertEquals("Chief Operating Officer", card.jobTitle)
    }

    @Test
    fun `falls back to keyword scan when no name-title line is directly adjacent`() {
        val lines = listOf(
            line("Jane Smith", top = 0, bottom = 20),
            line("Acme Solutions Ltd", top = 20, bottom = 40),
            line("Senior Consultant", top = 40, bottom = 60),
            line("jane.smith@acme.com", top = 60, bottom = 80),
        )
        val card = CardParser.parse(lines)
        assertEquals("Jane Smith", card.name)
        assertEquals("Senior Consultant", card.jobTitle)
    }

    @Test
    fun `splitLineIntoColumns separates a wide gap but keeps a normal word gap together`() {
        val wideGap = listOf(
            line("Wong", top = 0, left = 0, right = 100),
            line("Mee", top = 0, left = 110, right = 200),
            line("HWG", top = 0, left = 500, right = 600),
        )
        val splitWide = splitLineIntoColumns(wideGap, lineHeight = 20)
        assertEquals(2, splitWide.size)
        assertEquals("Wong Mee", splitWide[0].text)
        assertEquals("HWG", splitWide[1].text)

        val normalGap = listOf(
            line("Wong", top = 0, left = 0, right = 100),
            line("Mee", top = 0, left = 110, right = 200),
            line("Yoong", top = 0, left = 210, right = 300),
        )
        val splitNormal = splitLineIntoColumns(normalGap, lineHeight = 20)
        assertEquals(1, splitNormal.size)
        assertEquals("Wong Mee Yoong", splitNormal[0].text)
    }
}
