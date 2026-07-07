package com.eugeneboon.docscanner

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import com.eugeneboon.docscanner.data.ScanDocument
import com.eugeneboon.docscanner.ui.ScanEvent
import com.eugeneboon.docscanner.ui.ScanListScreen
import com.eugeneboon.docscanner.ui.ScanViewModel
import com.eugeneboon.docscanner.ui.theme.DocScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels { ScanViewModel.Factory }

    /**
     * Full scanner experience: live edge detection with auto-capture,
     * automatic cropping with manual adjustment, and cleanup filters
     * (shadow removal, stain removal, grayscale, auto-enhance).
     */
    private val scannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(50)
        .setResultFormats(
            GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
            GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
        )
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DocScannerTheme {
                val scans by viewModel.scans.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                val scannerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { activityResult ->
                    val result =
                        GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                    viewModel.onScanResult(result, application as DocScannerApp)
                }

                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/pdf")
                ) { uri -> viewModel.onExportDestination(uri) }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        val message = when (event) {
                            is ScanEvent.Saved ->
                                getString(R.string.scan_saved, event.readableSize)
                            is ScanEvent.Error ->
                                getString(R.string.scan_failed, event.message)
                            ScanEvent.Exported -> getString(R.string.exported)
                            ScanEvent.ExportFailed -> getString(R.string.export_failed)
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }

                ScanListScreen(
                    scans = scans,
                    snackbarHostState = snackbarHostState,
                    onScanClick = {
                        GmsDocumentScanning.getClient(scannerOptions)
                            .getStartScanIntent(this)
                            .addOnSuccessListener { intentSender ->
                                scannerLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                            .addOnFailureListener { e ->
                                viewModel.onScanError(e.message ?: "scanner unavailable")
                            }
                    },
                    onOpen = ::openPdf,
                    onShare = ::sharePdf,
                    onSaveToCloud = { scan ->
                        viewModel.requestExport(scan)
                        exportLauncher.launch("${scan.title}.pdf")
                    },
                    onRename = viewModel::rename,
                    onDelete = viewModel::delete,
                )
            }
        }
    }

    private fun contentUri(scan: ScanDocument): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", File(scan.pdfPath))

    private fun openPdf(scan: ScanDocument) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri(scan), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onScanError(getString(R.string.no_pdf_viewer))
        }
    }

    private fun sharePdf(scan: ScanDocument) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, contentUri(scan))
            putExtra(Intent.EXTRA_SUBJECT, scan.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}
