package com.ninja.scan.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Minimal PDF writer that embeds already-JPEG-compressed pages directly as
 * DCTDecode image XObjects — the structure scanner apps conventionally
 * produce.
 *
 * Android's own PdfDocument re-encodes every drawn bitmap losslessly, which
 * balloons a photographic page to several megabytes (a single composited ID
 * card page came out at ~15 MB); embedding the JPEG bytes as-is keeps each
 * page at its JPEG size with no additional quality loss on top of the JPEG
 * encoding the caller already chose.
 *
 * Pure JVM — no Android types — so the emitted structure is unit-testable.
 * Pages use the same "1 pixel = 1 PDF point" convention as the rest of the
 * app's PDFs.
 */
internal class JpegPdfWriter(private val target: File) {

    private val out = BufferedOutputStream(FileOutputStream(target))
    private var offset = 0L

    /** Byte offset of object i+1; slots for 1 (Catalog) and 2 (Pages) are reserved. */
    private val objectOffsets = mutableListOf(0L, 0L)
    private val pageObjectNumbers = mutableListOf<Int>()

    init {
        // The binary marker comment after the header tells consumers this
        // file contains binary data, per the PDF spec's recommendation.
        write("%PDF-1.4\n%âãÏÓ\n")
    }

    /** Records the next object's offset and returns its object number. */
    private fun beginObject(): Int {
        objectOffsets.add(offset)
        return objectOffsets.size
    }

    private fun write(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        offset += bytes.size
    }

    /** Appends one page showing [jpeg] stretched across a [width] x [height] page. */
    fun addPage(jpeg: ByteArray, width: Int, height: Int) {
        val imageObj = beginObject()
        write(
            "$imageObj 0 obj\n<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        )
        write(jpeg)
        write("\nendstream\nendobj\n")

        val content = "q $width 0 0 $height 0 0 cm /Im0 Do Q"
        val contentObj = beginObject()
        write("$contentObj 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

        val pageObj = beginObject()
        write(
            "$pageObj 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                "/Resources << /XObject << /Im0 $imageObj 0 R >> >> /Contents $contentObj 0 R >>\nendobj\n"
        )
        pageObjectNumbers.add(pageObj)
    }

    /**
     * Writes the document catalog, page tree, and xref, then closes the
     * file. Returns the number of pages written; with zero pages the
     * (invalid) file is deleted instead, matching how the callers treat an
     * empty result.
     */
    fun finish(): Int {
        if (pageObjectNumbers.isEmpty()) {
            out.close()
            target.delete()
            return 0
        }
        objectOffsets[0] = offset
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        objectOffsets[1] = offset
        write(
            "2 0 obj\n<< /Type /Pages /Kids [" +
                pageObjectNumbers.joinToString(" ") { "$it 0 R" } +
                "] /Count ${pageObjectNumbers.size} >>\nendobj\n"
        )
        val xrefOffset = offset
        write("xref\n0 ${objectOffsets.size + 1}\n")
        write("0000000000 65535 f \n")
        for (objectOffset in objectOffsets) {
            write(String.format(Locale.US, "%010d 00000 n \n", objectOffset))
        }
        write("trailer\n<< /Size ${objectOffsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        out.close()
        return pageObjectNumbers.size
    }
}
