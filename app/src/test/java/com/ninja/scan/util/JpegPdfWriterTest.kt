package com.ninja.scan.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JpegPdfWriterTest {

    private fun tempTarget(): File = File.createTempFile("jpegpdf", ".pdf").apply { deleteOnExit() }

    private fun fakeJpeg(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }.also {
            // JFIF SOI marker, so the payload at least starts like a JPEG.
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
        }

    @Test
    fun `writes a structurally consistent two-page document`() {
        val target = tempTarget()
        val writer = JpegPdfWriter(target)
        val pageOne = fakeJpeg(1000)
        val pageTwo = fakeJpeg(500)
        writer.addPage(pageOne, 100, 200)
        writer.addPage(pageTwo, 300, 400)
        assertEquals(2, writer.finish())

        val bytes = target.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 100 200]"))
        assertTrue(text.contains("/MediaBox [0 0 300 400]"))
        assertTrue(text.contains("/Filter /DCTDecode"))
        // Both JPEG payloads are embedded verbatim.
        assertTrue(indexOf(bytes, pageOne) >= 0)
        assertTrue(indexOf(bytes, pageTwo) >= 0)

        // Every xref entry's offset must point at that object's "N 0 obj".
        val xrefStart = text.lastIndexOf("xref")
        val entries = text.substring(xrefStart).lines()
            .filter { it.matches(Regex("\\d{10} \\d{5} [nf] ?")) }
        // Entry 0 is the free-list head; the rest are objects 1..N in order.
        entries.drop(1).forEachIndexed { index, entry ->
            val offset = entry.substringBefore(' ').toInt()
            val objectNumber = index + 1
            assertTrue(
                "object $objectNumber should start at offset $offset",
                text.startsWith("$objectNumber 0 obj", offset),
            )
        }
        // startxref points at the xref table itself.
        val startxref = text.substringAfterLast("startxref").trim().lines().first().trim().toInt()
        assertTrue(text.startsWith("xref", startxref))
    }

    @Test
    fun `finish with no pages deletes the file and reports zero`() {
        val target = tempTarget()
        val writer = JpegPdfWriter(target)
        assertEquals(0, writer.finish())
        assertFalse(target.exists())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
