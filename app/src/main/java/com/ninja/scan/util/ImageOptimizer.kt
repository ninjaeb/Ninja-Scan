package com.ninja.scan.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Produces cloud-friendly output from the scanner's full-resolution pages:
 * pages are bounded to [MAX_PAGE_DIMENSION_PX] (roughly A4 at 300 DPI, plenty
 * for archival and OCR) and re-encoded as JPEG at [PAGE_JPEG_QUALITY], then
 * assembled into a single PDF. This typically shrinks uploads by 3-10x with
 * no visible quality loss on documents.
 */
object ImageOptimizer {

    private const val MAX_PAGE_DIMENSION_PX = 2480
    private const val PAGE_JPEG_QUALITY = 85
    private const val THUMBNAIL_DIMENSION_PX = 512
    private const val THUMBNAIL_JPEG_QUALITY = 80

    // A4 at 300 DPI (2480x3508px) — the same "1 pixel = 1 PDF point" printable
    // page other PDFs here already use, just at a fixed size instead of one
    // sized to whatever the source image's own aspect ratio is.
    private const val ID_CARD_PAGE_WIDTH_PX = 2480
    private const val ID_CARD_PAGE_HEIGHT_PX = 3508

    // Not private: PdfEditor's ID-card watermark placement mirrors this same
    // front/back layout, so both stay derived from one source of truth.
    const val ID_CARD_MARGIN_RATIO = 0.06f
    const val ID_CARD_GAP_RATIO = 0.03f

    // ISO/IEC 7810 ID-1 format (standard ID/credit-card size): 85.60 x
    // 53.98mm, 2.88-3.18mm corner radius. Each side is rendered at this true
    // physical size and corner rounding — not stretched to fill the
    // available half of the page — so a printed copy matches a real card's
    // look and dimensions rather than an arbitrarily blown-up rectangle.
    private const val ID_CARD_LONG_MM = 85.60f
    private const val ID_CARD_SHORT_MM = 53.98f
    private const val ID_CARD_CORNER_RADIUS_MM = 3.18f
    private const val PX_PER_MM_AT_300_DPI = 300f / 25.4f

    // The card box as a fraction of the full page's width/height (assuming
    // the common landscape-held capture) — not private, so PdfEditor's
    // watermark can target the same small area the card actually occupies
    // instead of the whole half-page allocation around it, regardless of
    // what pixel size the page happens to be rendered at.
    val ID_CARD_WIDTH_FRACTION = ID_CARD_LONG_MM * PX_PER_MM_AT_300_DPI / ID_CARD_PAGE_WIDTH_PX
    val ID_CARD_HEIGHT_FRACTION = ID_CARD_SHORT_MM * PX_PER_MM_AT_300_DPI / ID_CARD_PAGE_HEIGHT_PX

    /**
     * Builds an optimized multi-page PDF from the given page image URIs.
     * Pages are embedded as JPEGs at [PAGE_JPEG_QUALITY] (see
     * [JpegPdfWriter] for why that matters for file size). Returns the
     * number of pages written.
     */
    fun writeOptimizedPdf(context: Context, pageUris: List<Uri>, target: File): Int {
        val writer = JpegPdfWriter(target)
        for (uri in pageUris) {
            val bitmap = decodeBounded(context, uri, MAX_PAGE_DIMENSION_PX) ?: continue
            writer.addPage(toJpeg(bitmap, PAGE_JPEG_QUALITY), bitmap.width, bitmap.height)
            bitmap.recycle()
        }
        return writer.finish()
    }

    /** [bitmap] as JPEG bytes, the form [JpegPdfWriter] pages embed. */
    internal fun toJpeg(bitmap: Bitmap, quality: Int = PAGE_JPEG_QUALITY): ByteArray =
        java.io.ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
        }.toByteArray()

    /**
     * Builds a single-page A4 PDF with an ID card's front and back stacked on
     * one page (front on top, back below), each rendered at true ID-1 card
     * size (~85.6 x 54mm) and centered in its half — a printed copy comes out
     * life-size instead of blown up to fill the page. [backUri] is optional
     * so a single-sided capture still produces a valid page. Returns whether
     * at least one side was decoded.
     */
    fun writeIdCardPdf(context: Context, frontUri: Uri, backUri: Uri?, target: File): Boolean {
        val page = compositeIdCardBitmap(context, frontUri, backUri) ?: return false
        val writer = JpegPdfWriter(target)
        writer.addPage(toJpeg(page, PAGE_JPEG_QUALITY), page.width, page.height)
        page.recycle()
        return writer.finish() > 0
    }

    /**
     * Composites [frontUri] and [backUri] onto one ID-card-formatted page
     * bitmap — front on top, back below, each at true ID-1 card size with
     * rounded corners, at the same fixed page dimensions [writeIdCardPdf]
     * wraps into a PDF. Exposed separately so a caller that needs the raw
     * page (e.g. splicing it into an existing multi-page PDF at its native
     * resolution, unlike the generic bounded page pipeline) doesn't have to
     * go through a temporary one-page PDF file. Returns null if neither side
     * could be decoded.
     */
    fun compositeIdCardBitmap(context: Context, frontUri: Uri, backUri: Uri?): Bitmap? {
        val front = decodeBounded(context, frontUri, MAX_PAGE_DIMENSION_PX)
        val back = backUri?.let { decodeBounded(context, it, MAX_PAGE_DIMENSION_PX) }
        if (front == null && back == null) return null

        val page = Bitmap.createBitmap(ID_CARD_PAGE_WIDTH_PX, ID_CARD_PAGE_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val marginX = (ID_CARD_PAGE_WIDTH_PX * ID_CARD_MARGIN_RATIO).toInt()
        val marginY = (ID_CARD_PAGE_HEIGHT_PX * ID_CARD_MARGIN_RATIO).toInt()
        val gap = (ID_CARD_PAGE_HEIGHT_PX * ID_CARD_GAP_RATIO).toInt()
        val usableWidth = ID_CARD_PAGE_WIDTH_PX - marginX * 2
        val halfHeight = (ID_CARD_PAGE_HEIGHT_PX - marginY * 2 - gap) / 2
        val cardLongPx = (ID_CARD_LONG_MM * PX_PER_MM_AT_300_DPI).toInt()
        val cardShortPx = (ID_CARD_SHORT_MM * PX_PER_MM_AT_300_DPI).toInt()

        front?.let {
            drawAtCardSize(
                canvas, it, paint, marginX, marginY, usableWidth, halfHeight, cardLongPx, cardShortPx,
            )
            it.recycle()
        }
        back?.let {
            drawAtCardSize(
                canvas, it, paint, marginX, marginY + halfHeight + gap, usableWidth, halfHeight,
                cardLongPx, cardShortPx,
            )
            it.recycle()
        }
        return page
    }

    /**
     * Draws [bitmap] at true ID-card size — [cardLongPx] x [cardShortPx], or
     * transposed to match [bitmap]'s own orientation — centered within the
     * region [x],[y],[boxW],[boxH], clipped to a rounded rectangle matching
     * a real card's corner radius. The image is never rotated: whichever way
     * it was captured is how it's drawn, only the target box is transposed
     * to fit that orientation. Fits within the card-size target (preserving
     * aspect ratio) rather than stretching to it exactly, in case the
     * scanner's auto-crop didn't land precisely on the ID-1 ratio.
     */
    private fun drawAtCardSize(
        canvas: Canvas,
        bitmap: Bitmap,
        paint: Paint,
        x: Int,
        y: Int,
        boxW: Int,
        boxH: Int,
        cardLongPx: Int,
        cardShortPx: Int,
    ) {
        val (targetW, targetH) =
            if (bitmap.width >= bitmap.height) cardLongPx to cardShortPx else cardShortPx to cardLongPx
        val scale = minOf(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = x + (boxW - drawWidth) / 2f
        val top = y + (boxH - drawHeight) / 2f
        val destRect = RectF(left, top, left + drawWidth, top + drawHeight)

        val cornerRadiusPx = ID_CARD_CORNER_RADIUS_MM * PX_PER_MM_AT_300_DPI
        canvas.save()
        canvas.clipPath(
            Path().apply { addRoundRect(destRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW) }
        )
        canvas.drawBitmap(bitmap, null, destRect, paint)
        canvas.restore()
    }

    /** Decodes [uri] bounded to [maxDimension] on its longest side. */
    fun decodeImage(context: Context, uri: Uri, maxDimension: Int): Bitmap? =
        decodeBounded(context, uri, maxDimension)

    /** Writes a small JPEG thumbnail of [pageUri] to [target]. */
    fun writeThumbnail(context: Context, pageUri: Uri, target: File): Boolean {
        val bitmap = decodeBounded(context, pageUri, THUMBNAIL_DIMENSION_PX) ?: return false
        FileOutputStream(target).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, it)
        }
        bitmap.recycle()
        return true
    }

    /**
     * Decodes [uri] with the longest side bounded to [maxDimension], using
     * sub-sampling first so full-resolution pages never sit in memory whole.
     */
    private fun decodeBounded(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver

        // decodeStream always returns null in inJustDecodeBounds mode — only
        // the stream being unopenable is a failure here; success is judged by
        // the dimensions written into `bounds`.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val headerStream = resolver.openInputStream(uri) ?: return null
        headerStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longest = maxOf(sampled.width, sampled.height)
        if (longest <= maxDimension) return sampled

        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createBitmap(
            (sampled.width * scale).toInt().coerceAtLeast(1),
            (sampled.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(scaled).drawBitmap(
            sampled,
            null,
            android.graphics.Rect(0, 0, scaled.width, scaled.height),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        sampled.recycle()
        return scaled
    }
}
