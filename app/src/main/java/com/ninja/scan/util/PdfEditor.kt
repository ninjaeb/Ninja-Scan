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

/**
 * One page of a spliced-together document: either an existing page of the
 * source PDF (re-rendered through the same bounded pipeline every other
 * page here goes through) or a bitmap inserted at its own native
 * resolution, unbounded — see [PdfEditor.splicePages].
 */
sealed interface SplicePage {
    data class Keep(val index: Int) : SplicePage
    data class Insert(val bitmap: Bitmap) : SplicePage
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

    /**
     * Rebuilds [sourcePdf] from [pages], each either an existing page
     * re-rendered through the usual bounded pipeline (matching how the rest
     * of the document already looks) or a bitmap inserted as its own page
     * at native resolution — unlike [rebuildPdf]'s image pages, an inserted
     * bitmap is NOT bounded to [MAX_PAGE_DIMENSION_PX], since it's already a
     * purpose-built page (see ImageOptimizer.compositeIdCardBitmap) that
     * must keep its exact physical size. Returns the number of pages
     * written.
     */
    fun splicePages(sourcePdf: File, pages: List<SplicePage>, target: File): Int {
        val document = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var pageNumber = 0
        try {
            openRenderer(sourcePdf).use { renderer ->
                for (spec in pages) {
                    val bitmap = when (spec) {
                        is SplicePage.Keep -> renderPage(renderer, spec.index, MAX_PAGE_DIMENSION_PX)
                        is SplicePage.Insert -> spec.bitmap
                    } ?: continue
                    pageNumber++
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, pageNumber)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    document.finishPage(page)
                    if (spec is SplicePage.Keep) bitmap.recycle()
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
     * [watermark] across every page. [isIdCard] targets the watermark at the
     * front/back card regions instead of the whole page — see [applyWatermark].
     * Returns the number of pages written.
     */
    fun rebuildPdf(
        context: Context,
        sourcePdf: File,
        pages: List<EditPage>,
        watermark: String?,
        target: File,
        isIdCard: Boolean = false,
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
                        val text = watermark.trim()
                        if (isIdCard) {
                            drawIdCardWatermark(page.canvas, bitmap.width, bitmap.height, text)
                        } else {
                            drawWatermark(page.canvas, bitmap.width, bitmap.height, text)
                        }
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

    /**
     * Stamps the diagonal watermark directly onto a mutable [bitmap]. For a
     * regular document this is one watermark spanning the whole page; for an
     * ID card scan ([isIdCard], see ScanRepository.saveIdCardScan) that would
     * put one giant watermark across a page that's mostly blank margin, so
     * instead two smaller watermarks are stamped directly over the front and
     * back card regions.
     */
    fun applyWatermark(bitmap: Bitmap, text: String, isIdCard: Boolean = false) {
        if (isIdCard) {
            drawIdCardWatermark(Canvas(bitmap), bitmap.width, bitmap.height, text.trim())
        } else {
            drawWatermark(Canvas(bitmap), bitmap.width, bitmap.height, text.trim())
        }
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
        isIdCard: Boolean = false,
    ): Int = rebuildPdf(
        context,
        source,
        List(pageCount(source)) { EditPage.FromPdf(it) },
        watermark,
        target,
        isIdCard,
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
        isIdCard: Boolean = false,
    ): List<File> {
        targetDir.mkdirs()
        val files = mutableListOf<File>()
        openRenderer(pdf).use { renderer ->
            for (index in pageIndices ?: (0 until renderer.pageCount).toList()) {
                val bitmap = renderPage(renderer, index, MAX_PAGE_DIMENSION_PX) ?: continue
                if (!watermark.isNullOrBlank()) applyWatermark(bitmap, watermark, isIdCard)
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
        isIdCard: Boolean = false,
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
                if (!watermark.isNullOrBlank()) applyWatermark(bitmap, watermark, isIdCard)
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
        drawWatermarkInRegion(canvas, 0, 0, width, height, text)
    }

    /**
     * An ID card scan's one page holds two card-sized regions (front on top,
     * back below — the same layout ImageOptimizer.writeIdCardPdf lays out),
     * so the watermark is stamped once per region, sized and positioned to
     * the actual card image — not the much larger half-page area around it
     * (the card only fills a fraction of that, at true physical size) —
     * instead of once diagonally across the whole mostly-blank page.
     */
    private fun drawIdCardWatermark(canvas: Canvas, width: Int, height: Int, text: String) {
        val marginX = (width * ImageOptimizer.ID_CARD_MARGIN_RATIO).toInt()
        val marginY = (height * ImageOptimizer.ID_CARD_MARGIN_RATIO).toInt()
        val gap = (height * ImageOptimizer.ID_CARD_GAP_RATIO).toInt()
        val usableWidth = width - marginX * 2
        val halfHeight = (height - marginY * 2 - gap) / 2

        // The true card size, centered within each half — same box
        // ImageOptimizer.drawAtCardSize fits the card image into.
        val cardWidth = (width * ImageOptimizer.ID_CARD_WIDTH_FRACTION).toInt().coerceAtMost(usableWidth)
        val cardHeight = (height * ImageOptimizer.ID_CARD_HEIGHT_FRACTION).toInt().coerceAtMost(halfHeight)
        val cardX = marginX + (usableWidth - cardWidth) / 2
        val frontCardY = marginY + (halfHeight - cardHeight) / 2
        val backCardY = marginY + halfHeight + gap + (halfHeight - cardHeight) / 2

        drawWatermarkInRegion(canvas, cardX, frontCardY, cardWidth, cardHeight, text)
        drawWatermarkInRegion(canvas, cardX, backCardY, cardWidth, cardHeight, text)
    }

    /** Draws [text] as a diagonal watermark centered within the given region. */
    private fun drawWatermarkInRegion(
        canvas: Canvas,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        text: String,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 120, 120, 120)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // The text sits on a -35° line through the region's center; the
        // longest line that stays fully within the region is bounded by
        // both dimensions.
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
        val centerX = x + width / 2f
        val centerY = y + height / 2f
        canvas.save()
        canvas.rotate(-35f, centerX, centerY)
        val baselineOffset = (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, centerX, centerY - baselineOffset, paint)
        canvas.restore()
    }

    private fun openRenderer(pdf: File): PdfRenderer =
        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY))
}
