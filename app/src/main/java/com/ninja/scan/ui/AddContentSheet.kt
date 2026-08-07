package com.ninja.scan.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninja.scan.R

/**
 * "How do you want to add to your library?" sheet: scan with the camera
 * (document / business card / ID card), or import an existing PDF or set of
 * images — one entry point behind the FAB instead of several stacked
 * scan-only buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContentSheet(
    onDismiss: () -> Unit,
    onScanDocument: () -> Unit,
    onScanBusinessCard: () -> Unit,
    onScanIdCard: () -> Unit,
    onImportPdf: () -> Unit,
    onImportImages: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.add_to_library),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetAction(
            Icons.Filled.DocumentScanner, stringResource(R.string.scan_document), ActionGreen, onScanDocument,
        )
        SheetAction(
            Icons.Filled.ContactPage, stringResource(R.string.scan_business_card), ActionGreen, onScanBusinessCard,
        )
        SheetAction(
            Icons.Filled.CreditCard, stringResource(R.string.scan_id_card), ActionGreen, onScanIdCard,
        )
        SheetAction(
            Icons.Filled.PictureAsPdf, stringResource(R.string.import_pdf), DestructiveRed, onImportPdf,
        )
        SheetAction(
            Icons.Filled.Image, stringResource(R.string.import_images), ShareBlue, onImportImages,
        )
        Spacer(Modifier.height(24.dp))
    }
}
