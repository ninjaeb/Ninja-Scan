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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import com.eugeneboon.docscanner.data.ScanDocument
import com.eugeneboon.docscanner.drive.DriveBackup
import com.eugeneboon.docscanner.editor.PageEditorActivity
import com.eugeneboon.docscanner.ui.ScanEvent
import com.eugeneboon.docscanner.ui.ScanListScreen
import com.eugeneboon.docscanner.ui.ScanViewModel
import com.eugeneboon.docscanner.ui.theme.DocScannerTheme
import com.eugeneboon.docscanner.viewer.PdfViewerActivity
import com.google.android.gms.auth.api.identity.Identity
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
        viewModel.setDriveBackupState(DriveBackup.isEnabled(this))
        setContent {
            DocScannerTheme {
                val scans by viewModel.scans.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val folders by viewModel.folders.collectAsState()
                val folderFilter by viewModel.folderFilter.collectAsState()
                val driveBackupEnabled by viewModel.driveBackupEnabled.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                val driveConsentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { activityResult ->
                    val granted = runCatching {
                        Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(activityResult.data)
                    }.isSuccess
                    if (granted) {
                        enableDriveBackup()
                    } else {
                        viewModel.emitEvent(ScanEvent.DriveBackupFailed("consent not granted"))
                    }
                }

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
                            ScanEvent.DriveBackupEnabled ->
                                getString(R.string.drive_backup_enabled)
                            ScanEvent.DriveBackupDisabled ->
                                getString(R.string.drive_backup_disabled)
                            is ScanEvent.DriveBackupFailed ->
                                getString(R.string.drive_backup_failed, event.message)
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }

                ScanListScreen(
                    scans = scans,
                    searchQuery = searchQuery,
                    folders = folders,
                    folderFilter = folderFilter,
                    driveBackupEnabled = driveBackupEnabled,
                    snackbarHostState = snackbarHostState,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onFolderFilterChange = viewModel::onFolderFilterChange,
                    onToggleDriveBackup = {
                        if (driveBackupEnabled) {
                            DriveBackup.setEnabled(this, false)
                            viewModel.setDriveBackupState(false)
                            viewModel.emitEvent(ScanEvent.DriveBackupDisabled)
                        } else {
                            requestDriveAuthorization { pendingIntent ->
                                driveConsentLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender)
                                        .build()
                                )
                            }
                        }
                    },
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
                    onOpen = { scan ->
                        startActivity(
                            PdfViewerActivity.intent(
                                this, scan.pdfPath, scan.title, scan.watermark
                            )
                        )
                    },
                    onOpenWith = ::openPdf,
                    onEdit = { scan ->
                        startActivity(PageEditorActivity.intent(this, scan.id))
                    },
                    onShare = ::sharePdf,
                    onShareAsImages = ::shareAsImages,
                    onSaveToCloud = { scan ->
                        viewModel.requestExport(scan)
                        exportLauncher.launch("${scan.title}.pdf")
                    },
                    onRename = viewModel::rename,
                    onMoveToFolder = viewModel::moveToFolder,
                    onDelete = viewModel::delete,
                )
            }
        }
    }

    /**
     * Requests the drive.file scope. If Google needs user consent (first
     * time), [onNeedsConsent] launches the returned system dialog; otherwise
     * backup is enabled immediately with the silently granted authorization.
     */
    private fun requestDriveAuthorization(
        onNeedsConsent: (android.app.PendingIntent) -> Unit,
    ) {
        Identity.getAuthorizationClient(this)
            .authorize(DriveBackup.authorizationRequest())
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    onNeedsConsent(pendingIntent)
                } else {
                    enableDriveBackup()
                }
            }
            .addOnFailureListener { e ->
                viewModel.emitEvent(
                    ScanEvent.DriveBackupFailed(e.message ?: "authorization unavailable")
                )
            }
    }

    private fun enableDriveBackup() {
        DriveBackup.setEnabled(this, true)
        viewModel.setDriveBackupState(true)
        viewModel.emitEvent(ScanEvent.DriveBackupEnabled)
    }

    private fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private val repository
        get() = (application as DocScannerApp).repository

    /** Opens the scan (watermarked if set) in an external PDF app. */
    private fun openPdf(scan: ScanDocument) {
        lifecycleScope.launch {
            val file = runCatching { repository.preparePdfForSharing(scan) }.getOrNull()
                ?: return@launch
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri(file), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                viewModel.onScanError(getString(R.string.no_pdf_viewer))
            }
        }
    }

    private fun sharePdf(scan: ScanDocument) {
        lifecycleScope.launch {
            val file = runCatching { repository.preparePdfForSharing(scan) }.getOrNull()
                ?: return@launch
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri(file))
                putExtra(Intent.EXTRA_SUBJECT, scan.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    /** Shares the scan's pages as JPEG images (watermarked if set). */
    private fun shareAsImages(scan: ScanDocument) {
        lifecycleScope.launch {
            val files = runCatching { repository.preparePageImages(scan) }.getOrNull()
            if (files.isNullOrEmpty()) return@launch
            val uris = ArrayList(files.map { contentUri(it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/jpeg"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                putExtra(Intent.EXTRA_SUBJECT, scan.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_as_images)))
        }
    }
}
