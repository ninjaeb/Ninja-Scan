package com.ninja.scan.util

import com.google.mlkit.vision.text.Text

/** Converts an ML Kit OCR result into layout-aware [OcrLine]s for CardParser. */
object OcrLayout {

    fun buildOcrLines(result: Text): List<OcrLine> {
        val out = mutableListOf<OcrLine>()
        var fallbackIndex = 0
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val elementLines = line.elements
                    .filter { it.text.isNotBlank() }
                    .mapNotNull { element ->
                        element.boundingBox?.let { box ->
                            OcrLine(element.text, box.left, box.top, box.right, box.bottom)
                        }
                    }
                when {
                    elementLines.size >= 2 -> {
                        val lineHeight = line.boundingBox?.height()?.takeIf { it > 0 }
                            ?: elementLines.map { it.height }.filter { it > 0 }
                                .let { heights -> if (heights.isEmpty()) 1 else heights.average().toInt() }
                        out += splitLineIntoColumns(elementLines, lineHeight.coerceAtLeast(1))
                    }
                    line.boundingBox != null -> {
                        val box = line.boundingBox!!
                        out += OcrLine(line.text.trim(), box.left, box.top, box.right, box.bottom)
                    }
                    else -> {
                        // No bounding box at all: preserve order via an
                        // incrementing synthetic top, disabling height-based
                        // ranking for this line only.
                        out += OcrLine(line.text.trim(), 0, fallbackIndex, 0, fallbackIndex)
                        fallbackIndex++
                    }
                }
            }
        }
        return out.filter { it.text.isNotEmpty() }
    }
}
