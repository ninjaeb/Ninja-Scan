package com.ninja.scan.util

/**
 * One physical line (or split column segment) of OCR text with its
 * bounding box. Plain Ints on purpose — no android.graphics.Rect — so this
 * stays pure Kotlin and unit-testable on the plain JVM without Robolectric.
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * Splits the elements of one physical OCR line into separate [OcrLine]s
 * wherever the horizontal gap between adjacent elements indicates a
 * distinct column — e.g. a person's name and job title printed side by
 * side on a business card, which ML Kit reports as a single text line.
 *
 * [elements] must be the individual elements of one line, sorted left to
 * right. [lineHeight] (that line's height, or an estimate) scales the gap
 * threshold so the heuristic adapts to the card's font size.
 */
fun splitLineIntoColumns(elements: List<OcrLine>, lineHeight: Int): List<OcrLine> {
    if (elements.isEmpty()) return emptyList()
    val sorted = elements.sortedBy { it.left }
    val threshold = maxOf((lineHeight * COLUMN_GAP_HEIGHT_MULTIPLIER).toInt(), MIN_COLUMN_GAP_PX)

    val clusters = mutableListOf<MutableList<OcrLine>>(mutableListOf(sorted.first()))
    for (i in 1 until sorted.size) {
        val previous = clusters.last().last()
        val current = sorted[i]
        if (current.left - previous.right > threshold) {
            clusters.add(mutableListOf(current))
        } else {
            clusters.last().add(current)
        }
    }

    return clusters.map { cluster ->
        OcrLine(
            text = cluster.joinToString(" ") { it.text },
            left = cluster.minOf { it.left },
            top = cluster.minOf { it.top },
            right = cluster.maxOf { it.right },
            bottom = cluster.maxOf { it.bottom },
        )
    }
}

private const val COLUMN_GAP_HEIGHT_MULTIPLIER = 1.8
private const val MIN_COLUMN_GAP_PX = 24
