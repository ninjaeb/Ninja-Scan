package com.ninja.scan.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * One page of an edited document: either an existing page of the source PDF
 * or a freshly scanned image, plus a clockwise rotation in degrees.
 */
sealed interface EditPage {
    val rotation: Int

    data class FromPdf(val index: Int, override val rotation: Int = 0) : EditPage
    data class FromImage(val uri: Uri, override val rotation: Int = 0) : EditPage
}

fun EditPage.rotatedClockwise(): EditPage = when (this) {
    is EditPage.FromPdf -> copy(rotation = (rotation + 90) % 360)
    is EditPage.FromImage -> copy(rotation = (rotation + 90) % 360)
}

/** Rebuilds and renders scan PDFs for the page editor and OCR re-runs. */
object PdfEditor {

    private const val MAX_PAGE_DIMENSION_PX = 2480
    private const val THUMBNAIL_DIMENSION_PX = 512
    private const val THUMBNAIL_JPEG_QUALITY = 80
    private const val SHARE_JPEG_QUALITY = 85
    private const val LONG_IMAGE_WIDTH_PX = 900
    private const val LONG_IMAGE_MAX_HEIGHT_PX = 14000
    private const val LONG_IMAGE_JPEG_QUALITY = 80

    fun pageCount(pdf: File): Int =
        runCatching {
            openRenderer(pdf).use { it.pageCount }
        }.getOrDefault(0)

    /** Renders one page of [pdf], already rotated, for editor thumbnails. */
    fun renderPageFromFile(pdf: File, index: Int, maxDimension: Int, rotation: Int): Bitmap? =
        runCatching {
            openRenderer(pdf).use { renderer ->
                renderPage(renderer, index, maxDimension)?.let { rotate(it, rotation) }
            }
        }.getOrNull()

    fun renderPage(renderer: PdfRenderer, index: Int, maxDimension: Int): Bitmap? {
        if (index !in 0 until renderer.pageCount) return null
        return renderer.openPage(index).use { page ->
            if (page.width <= 0 || page.height <= 0) return null
            val scale = maxDimension.toFloat() / maxOf(page.width, page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bitmap
        }
    }

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Writes a new PDF assembled from [pages] (source-PDF pages and/or new
     * images), applying per-page rotation and an optional diagonal text
     * [watermark] across every page. Returns the number of pages written.
     */
    fun rebuildPdf(
        context: Context,
        sourcePdf: File,
        pages: List<EditPage>,
        watermark: String?,
        target: File,
    ): Int {
        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var pageNumber = 0
        try {
            openRenderer(sourcePdf).use { renderer ->
                for (spec in pages) {
                    val source = when (spec) {
                        is EditPage.FromPdf ->
                            renderPage(renderer, spec.index, MAX_PAGE_DIMENSION_PX)
                        is EditPage.FromImage ->
                            ImageOptimizer.decodeImage(context, spec.uri, MAX_PAGE_DIMENSION_PX)
                    } ?: continue
                    val bitmap = rotate(source, spec.rotation)
                    pageNumber++
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, pageNumber)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    if (!watermark.isNullOrBlank()) {
                        drawWatermark(page.canvas, bitmap.width, bitmap.height, watermark.trim())
                    }
                    document.finishPage(page)
                    bitmap.recycle()
                }
            }
            if (pageNumber > 0) {
                FileOutputStream(target).use { document.writeTo(it) }
            }
        } finally {
            document.close()
        }
        return pageNumber
    }

    /** Stamps the diagonal watermark directly onto a mutable [bitmap]. */
    fun applyWatermark(bitmap: Bitmap, text: String) {
        drawWatermark(Canvas(bitmap), bitmap.width, bitmap.height, text.trim())
    }

    /**
     * Writes a copy of [source] with [watermark] stamped on every page.
     * Returns the number of pages written.
     */
    fun writeWatermarkedCopy(
        context: Context,
        source: File,
        watermark: String,
        target: File,
    ): Int = rebuildPdf(
        context,
        source,
        List(pageCount(source)) { EditPage.FromPdf(it) },
        watermark,
        target,
    )

    /**
     * Renders every page of [pdf] as a JPEG in [targetDir] (optionally
     * watermarked) and returns the files in page order.
     */
    fun renderPagesAsJpegs(
        pdf: File,
        watermark: String?,
        targetDir: File,
        baseName: String,
        pageIndices: List<Int>? = null,
    ): List<File> {
        targetDir.mkdirs()
        val files = mutableListOf<File>()
        openRenderer(pdf).use { renderer ->
            for (index in pageIndices ?: (0 until renderer.pageCount).toList()) {
                val bitmap = renderPage(renderer, index, MAX_PAGE_DIMENSION_PX) ?: continue
                if (!watermark.isNullOrBlank()) applyWatermark(bitmap, watermark)
                val file = File(targetDir, "$baseName-${index + 1}.jpg")
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, SHARE_JPEG_QUALITY, it)
                }
                bitmap.recycle()
                files.add(file)
            }
        }
        return files
    }

    /**
     * Stitches every page of [pdf] into one tall JPEG ("long image"),
     * optionally watermarked. The width targets [LONG_IMAGE_WIDTH_PX] and the
     * total height is capped by down-scaling. Returns the page count drawn.
     */
    fun writeLongImage(
        pdf: File,
        watermark: String?,
        target: File,
        pageIndices: List<Int>? = null,
    ): Int {
        openRenderer(pdf).use { renderer ->
            val indices = pageIndices ?: (0 until renderer.pageCount).toList()
            if (indices.isEmpty()) return 0
            // Pairs each page number with its size so a subset (or any page
            // dropped by the size filter below) can't desync page number from
            // position, unlike iterating a plain 0-until-count range.
            val sized = indices.mapNotNull { index ->
                val (w, h) = renderer.openPage(index).use { it.width to it.height }
                if (w > 0 && h > 0) index to (w to h) else null
            }
            if (sized.isEmpty()) return 0

            var width = LONG_IMAGE_WIDTH_PX
            var totalHeight = sized.sumOf { (_, wh) -> wh.second * width / wh.first }
            if (totalHeight > LONG_IMAGE_MAX_HEIGHT_PX) {
                width = (width.toLong() * LONG_IMAGE_MAX_HEIGHT_PX / totalHeight)
                    .toInt().coerceAtLeast(200)
                totalHeight = sized.sumOf { (_, wh) -> wh.second * width / wh.first }
            }

            val sheet = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
            sheet.eraseColor(Color.WHITE)
            val canvas = Canvas(sheet)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var y = 0
            var drawn = 0
            for ((pageIndex, size) in sized) {
                val (pageWidth, pageHeight) = size
                val scaledHeight = (pageHeight * width / pageWidth).coerceAtLeast(1)
                val bitmap = renderer.openPage(pageIndex).use { page ->
                    val b = Bitmap.createBitmap(width, scaledHeight, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    b
                }
                if (!watermark.isNullOrBlank()) applyWatermark(bitmap, watermark)
                canvas.drawBitmap(bitmap, 0f, y.toFloat(), paint)
                bitmap.recycle()
                y += scaledHeight
                drawn++
            }
            FileOutputStream(target).use {
                sheet.compress(Bitmap.CompressFormat.JPEG, LONG_IMAGE_JPEG_QUALITY, it)
            }
            sheet.recycle()
            return drawn
        }
    }

    /** Renders the first page of [pdf] as a small JPEG thumbnail. */
    fun writeThumbnail(pdf: File, target: File): Boolean =
        runCatching {
            openRenderer(pdf).use { renderer ->
                val bitmap = renderPage(renderer, 0, THUMBNAIL_DIMENSION_PX) ?: return false
                FileOutputStream(target).use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, it)
                }
                bitmap.recycle()
                true
            }
        }.getOrDefault(false)

    private fun drawWatermark(canvas: Canvas, width: Int, height: Int, text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 120, 120, 120)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // The text sits on a -35° line through the page center; the longest
        // line that stays fully on the page is bounded by both dimensions.
        val angle = Math.toRadians(35.0)
        val maxLineWidth =
            (minOf(width / Math.cos(angle), height / Math.sin(angle)) * 0.9).toFloat()
        paint.textSize = 100f
        val measured = paint.measureText(text).coerceAtLeast(1f)
        paint.textSize = (100f * maxLineWidth / measured).coerceIn(width / 40f, width / 6f)
        // If even the floor size overflows (extremely long text), shrink below
        // the floor rather than clip — the watermark must stay fully visible.
        if (paint.measureText(text) > maxLineWidth) {
            paint.textSize = paint.textSize * maxLineWidth / paint.measureText(text)
        }
        canvas.save()
        canvas.rotate(-35f, width / 2f, height / 2f)
        val baselineOffset = (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, width / 2f, height / 2f - baselineOffset, paint)
        canvas.restore()
    }

    private fun openRenderer(pdf: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY))
}
