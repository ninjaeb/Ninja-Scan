package com.ninja.scan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import com.ninja.scan.about.AboutActivity
import com.ninja.scan.cards.CardsActivity
import com.ninja.scan.drive.DriveBackup
import com.ninja.scan.drive.DriveBackupWorker
import com.ninja.scan.drive.DriveRestoreWorker
import com.ninja.scan.ui.ScanEvent
import com.ninja.scan.ui.ScanListScreen
import com.ninja.scan.ui.ScanViewModel
import com.ninja.scan.ui.SyncProgress
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.ThemePrefs
import com.ninja.scan.util.ShareActions
import com.ninja.scan.viewer.PdfViewerActivity
import com.google.android.gms.auth.api.identity.Identity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels { ScanViewModel.Factory }

    /** What to do once Drive consent is granted: enable backup or restore. */
    private var pendingDriveAction = DriveAction.ENABLE_BACKUP

    private enum class DriveAction { ENABLE_BACKUP, RESTORE }

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

    /**
     * Capped at 2 pages (front, then back) — the pair is composited onto one
     * printable page ourselves, so there's no need for the scanner's own PDF.
     */
    private val idCardScannerOptions = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(true)
        .setPageLimit(2)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setDriveBackupState(DriveBackup.isEnabled(this))
        setContent {
            var isDarkTheme by remember { mutableStateOf(ThemePrefs.isDark(this)) }
            // Cards and Documents are separate Activities; re-read the shared
            // preference on every resume so a toggle made on one screen is
            // reflected here after navigating back, not just at creation time.
            LifecycleResumeEffect(Unit) {
                isDarkTheme = ThemePrefs.isDark(this@MainActivity)
                onPauseOrDispose { }
            }
            DocScannerTheme(darkTheme = isDarkTheme) {
                val scans by viewModel.scans.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val folders by viewModel.folders.collectAsState()
                val folderColors by viewModel.folderColors.collectAsState()
                val folderFilter by viewModel.folderFilter.collectAsState()
                val driveBackupEnabled by viewModel.driveBackupEnabled.collectAsState()
                val justSaved by viewModel.justSaved.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                var showRestoreOffer by remember { mutableStateOf(false) }
                var lastRestoreState by remember { mutableStateOf<WorkInfo.State?>(null) }
                var lastBackupState by remember { mutableStateOf<WorkInfo.State?>(null) }
                var restoreProgress by remember { mutableStateOf<SyncProgress?>(null) }
                var backupProgress by remember { mutableStateOf<SyncProgress?>(null) }

                val driveConsentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { activityResult ->
                    val granted = runCatching {
                        Identity.getAuthorizationClient(this)
                            .getAuthorizationResultFromIntent(activityResult.data)
                    }.isSuccess
                    if (granted) {
                        performPendingDriveAction()
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

                val idCardScannerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { activityResult ->
                    val result =
                        GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
                    viewModel.onIdCardScanResult(result, application as DocScannerApp)
                }

                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/pdf")
                ) { uri -> viewModel.onExportDestination(uri) }

                val importPdfLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> viewModel.onImportPdf(uri, application as DocScannerApp) }

                val importImagesLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetMultipleContents()
                ) { uris -> viewModel.onImportImages(uris, application as DocScannerApp) }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        if (event is ScanEvent.DriveBackupEnabled && scans.isEmpty()) {
                            showRestoreOffer = true
                        }
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
                            ScanEvent.DriveBackupStarted ->
                                getString(R.string.drive_backup_started)
                            is ScanEvent.DriveBackupFailed ->
                                getString(R.string.drive_backup_failed, event.message)
                            is ScanEvent.DriveBackupCompleted ->
                                getString(R.string.drive_backup_done, event.scans, event.cards)
                            is ScanEvent.DriveBackupIncomplete ->
                                getString(R.string.drive_backup_incomplete, event.failures)
                            ScanEvent.DriveRestoreStarted ->
                                getString(R.string.drive_restore_started)
                            is ScanEvent.DriveRestoreCompleted ->
                                getString(R.string.drive_restore_done, event.scans, event.cards)
                            ScanEvent.DriveRestoreFailed ->
                                getString(R.string.drive_restore_failed)
                        }
                        snackbarHostState.showSnackbar(message)
                    }
                }

                // Surfaces the restore worker's terminal state as a one-shot
                // event; WorkInfo persists after completion, so only a state
                // transition (not every recomposition) triggers a snackbar.
                LaunchedEffect(Unit) {
                    DriveBackup.restoreWorkInfo(this@MainActivity).collect { infos ->
                        val info = infos.firstOrNull() ?: return@collect
                        restoreProgress = if (info.state == WorkInfo.State.RUNNING) {
                            SyncProgress(
                                current = info.progress.getInt(DriveBackup.KEY_PROGRESS_CURRENT, 0),
                                total = info.progress.getInt(DriveBackup.KEY_PROGRESS_TOTAL, 0),
                            )
                        } else {
                            null
                        }
                        if (info.state == lastRestoreState) return@collect
                        lastRestoreState = info.state
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> viewModel.emitEvent(
                                ScanEvent.DriveRestoreCompleted(
                                    scans = info.outputData.getInt(DriveRestoreWorker.KEY_SCANS, 0),
                                    cards = info.outputData.getInt(DriveRestoreWorker.KEY_CARDS, 0),
                                )
                            )
                            WorkInfo.State.FAILED ->
                                viewModel.emitEvent(ScanEvent.DriveRestoreFailed)
                            WorkInfo.State.ENQUEUED ->
                                viewModel.emitEvent(ScanEvent.DriveRestoreStarted)
                            else -> {}
                        }
                    }
                }

                // A routine background op, so only a genuine terminal state
                // change (not every recomposition) surfaces a snackbar — and
                // only when there was something to report (avoids a snackbar
                // on every no-op periodic run).
                LaunchedEffect(Unit) {
                    DriveBackup.backupWorkInfo(this@MainActivity).collect { infos ->
                        val info = infos.firstOrNull() ?: return@collect
                        backupProgress = if (info.state == WorkInfo.State.RUNNING) {
                            SyncProgress(
                                current = info.progress.getInt(DriveBackup.KEY_PROGRESS_CURRENT, 0),
                                total = info.progress.getInt(DriveBackup.KEY_PROGRESS_TOTAL, 0),
                            )
                        } else {
                            null
                        }
                        if (info.state == lastBackupState) return@collect
                        lastBackupState = info.state
                        when (info.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val scans = info.outputData.getInt(DriveBackupWorker.KEY_SCANS_BACKED_UP, 0)
                                val cards = info.outputData.getInt(DriveBackupWorker.KEY_CARDS_BACKED_UP, 0)
                                if (scans > 0 || cards > 0) {
                                    viewModel.emitEvent(ScanEvent.DriveBackupCompleted(scans, cards))
                                }
                            }
                            WorkInfo.State.FAILED -> {
                                val failures = info.outputData.getInt(DriveBackupWorker.KEY_FAILURES, 0)
                                if (failures > 0) {
                                    viewModel.emitEvent(ScanEvent.DriveBackupIncomplete(failures))
                                }
                            }
                            else -> {}
                        }
                    }
                }

                ScanListScreen(
                    scans = scans,
                    searchQuery = searchQuery,
                    folders = folders,
                    folderColors = folderColors,
                    folderFilter = folderFilter,
                    driveBackupEnabled = driveBackupEnabled,
                    justSaved = justSaved,
                    snackbarHostState = snackbarHostState,
                    restoreProgress = restoreProgress,
                    backupProgress = backupProgress,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        ThemePrefs.setDark(this, isDarkTheme)
                    },
                    onConfirmScanDetails = viewModel::confirmScanDetails,
                    onDismissScanDetails = viewModel::dismissScanDetails,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onFolderFilterChange = viewModel::onFolderFilterChange,
                    onOpenCards = {
                        startActivity(CardsActivity.intent(this))
                        // Continues the swipe's motion into the activity
                        // transition instead of a plain cut: Cards slides in
                        // from the left (the direction the finger dragged),
                        // Documents slides out to the right.
                        @Suppress("DEPRECATION")
                        overridePendingTransition(R.anim.slide_in_from_left, R.anim.slide_out_to_right)
                    },
                    onOpenAbout = { startActivity(AboutActivity.intent(this)) },
                    onScanCardClick = {
                        startActivity(CardsActivity.intent(this, startScan = true))
                    },
                    onToggleDriveBackup = {
                        if (driveBackupEnabled) {
                            DriveBackup.setEnabled(this, false)
                            viewModel.setDriveBackupState(false)
                            viewModel.emitEvent(ScanEvent.DriveBackupDisabled)
                        } else {
                            pendingDriveAction = DriveAction.ENABLE_BACKUP
                            requestDriveAuthorization { pendingIntent ->
                                driveConsentLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender)
                                        .build()
                                )
                            }
                        }
                    },
                    onRestoreFromDrive = {
                        pendingDriveAction = DriveAction.RESTORE
                        requestDriveAuthorization { pendingIntent ->
                            driveConsentLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        }
                    },
                    onBackupNowDrive = {
                        DriveBackup.enqueue(this)
                        viewModel.emitEvent(ScanEvent.DriveBackupStarted)
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
                    onScanIdCardClick = {
                        GmsDocumentScanning.getClient(idCardScannerOptions)
                            .getStartScanIntent(this)
                            .addOnSuccessListener { intentSender ->
                                idCardScannerLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                            .addOnFailureListener { e ->
                                viewModel.onScanError(e.message ?: "scanner unavailable")
                            }
                    },
                    onImportPdfClick = { importPdfLauncher.launch("application/pdf") },
                    onImportImagesClick = { importImagesLauncher.launch("image/*") },
                    onOpen = { scan ->
                        startActivity(PdfViewerActivity.intent(this, scan.id))
                    },
                    onSharePdf = { ShareActions.sharePdf(this, it) },
                    onShareImages = { ShareActions.shareImages(this, it) },
                    onShareLongImage = { ShareActions.shareLongImage(this, it) },
                    onShareSeparatePdfs = { ShareActions.shareSeparatePdfs(this, it) },
                    onSharePdfs = { ShareActions.sharePdfs(this, it) },
                    onShareImagesMulti = { ShareActions.shareImagesMulti(this, it) },
                    onShareLongImageMulti = { ShareActions.shareLongImageMulti(this, it) },
                    onShareSeparatePdfsMulti = { ShareActions.shareSeparatePdfsMulti(this, it) },
                    onSaveToCloud = { scan ->
                        viewModel.requestExport(scan)
                        exportLauncher.launch("${scan.title}.pdf")
                    },
                    onRename = viewModel::rename,
                    onMoveToFolder = viewModel::moveToFolder,
                    onDelete = viewModel::delete,
                    onDeleteScans = viewModel::deleteScans,
                    onAddFolder = viewModel::addFolder,
                    onRenameFolder = viewModel::renameFolder,
                    onDeleteFolder = viewModel::deleteFolder,
                )

                if (showRestoreOffer) {
                    AlertDialog(
                        onDismissRequest = { showRestoreOffer = false },
                        title = { Text(stringResource(R.string.drive_restore_offer_title)) },
                        text = { Text(stringResource(R.string.drive_restore_offer_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showRestoreOffer = false
                                DriveBackup.enqueueRestore(this)
                            }) {
                                Text(stringResource(R.string.restore))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestoreOffer = false }) {
                                Text(stringResource(R.string.not_now))
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Requests the drive.file scope for whichever action was just requested
     * ([pendingDriveAction]). If Google needs user consent (first time),
     * [onNeedsConsent] launches the returned system dialog; otherwise the
     * pending action runs immediately with the silently granted authorization.
     */
    private fun requestDriveAuthorization(
        onNeedsConsent: (android.app.PendingIntent) -> Unit,
    ) {
        DriveBackup.requestAuthorization(
            context = this,
            onNeedsConsent = onNeedsConsent,
            onGranted = { performPendingDriveAction() },
            onFailure = { message -> viewModel.emitEvent(ScanEvent.DriveBackupFailed(message)) },
        )
    }

    private fun performPendingDriveAction() {
        when (pendingDriveAction) {
            DriveAction.ENABLE_BACKUP -> enableDriveBackup()
            DriveAction.RESTORE -> DriveBackup.enqueueRestore(this)
        }
    }

    private fun enableDriveBackup() {
        DriveBackup.setEnabled(this, true)
        viewModel.setDriveBackupState(true)
        viewModel.emitEvent(ScanEvent.DriveBackupEnabled)
    }
}
