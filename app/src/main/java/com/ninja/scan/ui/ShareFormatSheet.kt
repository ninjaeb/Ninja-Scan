package com.ninja.scan.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninja.scan.R
import com.ninja.scan.data.ScanDocument
import java.io.File

/**
 * "How do you want to share this?" sheet: PDF, per-page images, one tall
 * long image, or every page as its own PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFormatSheet(
    scan: ScanDocument,
    onDismiss: () -> Unit,
    onPdf: () -> Unit,
    onImages: () -> Unit,
    onLongImage: () -> Unit,
    onSeparatePdfs: () -> Unit,
) {
    ShareFormatSheetBody(
        header = { ScanSheetHeader(scan) },
        onDismiss = onDismiss,
        onPdf = onPdf,
        onImages = onImages,
        onLongImage = onLongImage,
        onSeparatePdfs = onSeparatePdfs,
    )
}

/** Same format choices, batched over several selected documents at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFormatSheet(
    scans: List<ScanDocument>,
    onDismiss: () -> Unit,
    onPdf: () -> Unit,
    onImages: () -> Unit,
    onLongImage: () -> Unit,
    onSeparatePdfs: () -> Unit,
) {
    ShareFormatSheetBody(
        header = { MultiScanSheetHeader(scans) },
        onDismiss = onDismiss,
        onPdf = onPdf,
        onImages = onImages,
        onLongImage = onLongImage,
        onSeparatePdfs = onSeparatePdfs,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareFormatSheetBody(
    header: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onPdf: () -> Unit,
    onImages: () -> Unit,
    onLongImage: () -> Unit,
    onSeparatePdfs: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        header()
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetAction(Icons.Filled.PictureAsPdf, stringResource(R.string.share_as_pdf), onClick = onPdf)
        SheetAction(Icons.Filled.Image, stringResource(R.string.share_as_images), onClick = onImages)
        SheetAction(Icons.Filled.Photo, stringResource(R.string.share_as_long_image), onClick = onLongImage)
        SheetAction(
            Icons.AutoMirrored.Filled.ViewList,
            stringResource(R.string.export_separate_pdfs),
            onClick = onSeparatePdfs,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun ScanSheetHeader(scan: ScanDocument) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnail = scan.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(scan.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            val pages =
                if (scan.pageCount == 1) stringResource(R.string.page)
                else stringResource(R.string.pages, scan.pageCount)
            Text(
                "$pages · ${Formatter.formatShortFileSize(context, scan.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MultiScanSheetHeader(scans: List<ScanDocument>) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                stringResource(R.string.documents_selected, scans.size),
                style = MaterialTheme.typography.titleMedium,
            )
            val totalPages = scans.sumOf { it.pageCount }
            val pages =
                if (totalPages == 1) stringResource(R.string.page)
                else stringResource(R.string.pages, totalPages)
            val totalSize = scans.sumOf { it.sizeBytes }
            Text(
                "$pages · ${Formatter.formatShortFileSize(context, totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SheetAction(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** "How do you want to save this?" sheet: PDF, or every page as an image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveFormatSheet(
    scan: ScanDocument,
    onDismiss: () -> Unit,
    onPdf: () -> Unit,
    onImages: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ScanSheetHeader(scan)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetAction(Icons.Filled.PictureAsPdf, stringResource(R.string.save_as_pdf), onClick = onPdf)
        SheetAction(Icons.Filled.Image, stringResource(R.string.save_as_images), onClick = onImages)
        Spacer(Modifier.height(24.dp))
    }
}

/** "How do you want to export these pages?" sheet, for a page subset picked in the viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesShareSheet(
    pageCount: Int,
    onDismiss: () -> Unit,
    onPdf: () -> Unit,
    onImages: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                if (pageCount == 1) stringResource(R.string.page)
                else stringResource(R.string.pages, pageCount),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetAction(Icons.Filled.PictureAsPdf, stringResource(R.string.share_as_pdf), onClick = onPdf)
        SheetAction(Icons.Filled.Image, stringResource(R.string.share_as_images), onClick = onImages)
        Spacer(Modifier.height(24.dp))
    }
}
