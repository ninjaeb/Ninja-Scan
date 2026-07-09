package com.ninja.scan.cards

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.WorkInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.ninja.scan.DocScannerApp
import com.ninja.scan.R
import com.ninja.scan.data.BusinessCard
import com.ninja.scan.data.Tag
import com.ninja.scan.drive.DriveBackup
import com.ninja.scan.drive.DriveBackupWorker
import com.ninja.scan.ui.ActionGreen
import com.ninja.scan.ui.AppTitleWithIcon
import com.ninja.scan.ui.DestructiveRed
import com.ninja.scan.ui.DriveMenuButton
import com.ninja.scan.ui.DriveSyncProgressBar
import com.ninja.scan.ui.EditAmber
import com.ninja.scan.ui.HintPrefs
import com.ninja.scan.ui.LongPressHint
import com.ninja.scan.ui.LongPressableChip
import com.ninja.scan.ui.ShareBlue
import com.ninja.scan.ui.SyncProgress
import com.ninja.scan.ui.ThemeToggleButton
import com.ninja.scan.ui.WhatsAppGreen
import com.ninja.scan.ui.brandedNavigationItemColors
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.ThemePrefs
import com.google.android.gms.auth.api.identity.Identity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Business card library: scan cards, manage contacts, export CSV/Excel. */
class CardsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoStartScan =
            savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_SCAN, false)
        setContent {
            var isDarkTheme by remember { mutableStateOf(ThemePrefs.isDark(this)) }
            // Documents and Cards are separate Activities; re-read the shared
            // preference on every resume so a toggle made on one screen is
            // reflected here after navigating back, not just at creation time.
            LifecycleResumeEffect(Unit) {
                isDarkTheme = ThemePrefs.isDark(this@CardsActivity)
                onPauseOrDispose { }
            }
            DocScannerTheme(darkTheme = isDarkTheme) {
                CardsScreen(
                    autoStartScan = autoStartScan,
                    onBack = {
                        finish()
                        // Continues the swipe's motion into the activity
                        // transition instead of a plain cut: Documents slides
                        // in from the right (the direction the finger
                        // dragged), Cards slides out to the left.
                        @Suppress("DEPRECATION")
                        overridePendingTransition(R.anim.slide_in_from_right, R.anim.slide_out_to_left)
                    },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        ThemePrefs.setDark(this, isDarkTheme)
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_START_SCAN = "start_scan"

        fun intent(context: Context, startScan: Boolean = false): Intent =
            Intent(context, CardsActivity::class.java)
                .putExtra(EXTRA_START_SCAN, startScan)
    }
}

private const val HINT_KEY_CARDS = "cards"

// FULL mode: better crop, shadow/stain cleanup, and auto-enhance produce a
// sharper image and noticeably better OCR field extraction.
private val cardScannerOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(1)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardsScreen(
    autoStartScan: Boolean,
    onBack: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScannerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var driveMenuOpen by remember { mutableStateOf(false) }
    var driveBackupEnabled by remember { mutableStateOf(DriveBackup.isEnabled(context)) }
    // What to do once Drive consent is granted: enable backup, or restore.
    var pendingDriveRestore by remember { mutableStateOf(false) }
    var restoreProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var backupProgress by remember { mutableStateOf<SyncProgress?>(null) }

    LaunchedEffect(Unit) {
        DriveBackup.restoreWorkInfo(context).collect { infos ->
            val info = infos.firstOrNull() ?: return@collect
            restoreProgress = if (info.state == WorkInfo.State.RUNNING) {
                SyncProgress(
                    current = info.progress.getInt(DriveBackup.KEY_PROGRESS_CURRENT, 0),
                    total = info.progress.getInt(DriveBackup.KEY_PROGRESS_TOTAL, 0),
                )
            } else {
                null
            }
        }
    }
    var lastBackupState by remember { mutableStateOf<WorkInfo.State?>(null) }
    LaunchedEffect(Unit) {
        DriveBackup.backupWorkInfo(context).collect { infos ->
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
                    val backedUpScans = info.outputData.getInt(DriveBackupWorker.KEY_SCANS_BACKED_UP, 0)
                    val backedUpCards = info.outputData.getInt(DriveBackupWorker.KEY_CARDS_BACKED_UP, 0)
                    if (backedUpScans > 0 || backedUpCards > 0) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.drive_backup_done, backedUpScans, backedUpCards)
                            )
                        }
                    }
                }
                WorkInfo.State.FAILED -> {
                    val failures = info.outputData.getInt(DriveBackupWorker.KEY_FAILURES, 0)
                    if (failures > 0) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.drive_backup_incomplete, failures)
                            )
                        }
                    }
                }
                else -> {}
            }
        }
    }

    fun performPendingDriveAction() {
        if (pendingDriveRestore) {
            DriveBackup.enqueueRestore(context)
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.drive_restore_started))
            }
        } else {
            DriveBackup.setEnabled(context, true)
            driveBackupEnabled = true
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.drive_backup_enabled))
            }
        }
    }

    val driveConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val granted = runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
        }.isSuccess
        if (granted) {
            performPendingDriveAction()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.drive_backup_failed, "consent not granted")
                )
            }
        }
    }

    fun requestDriveAuthorization() {
        DriveBackup.requestAuthorization(
            context = context,
            onNeedsConsent = { pendingIntent ->
                driveConsentLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            },
            onGranted = { performPendingDriveAction() },
            onFailure = { message ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.drive_backup_failed, message)
                    )
                }
            },
        )
    }

    val cards by app.repository.cards.collectAsState(initial = emptyList())
    val allTags by app.repository.tags.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<BusinessCard?>(null) }
    var deleting by remember { mutableStateOf<BusinessCard?>(null) }
    var parsing by remember { mutableStateOf(false) }

    // Long-press a card to pick several and delete them together.
    val selectedCardIds = remember { mutableStateListOf<Long>() }
    val cardSelectionActive = selectedCardIds.isNotEmpty()
    var deletingCards by remember { mutableStateOf<List<BusinessCard>?>(null) }
    BackHandler(enabled = cardSelectionActive) { selectedCardIds.clear() }
    var showLongPressHint by remember {
        mutableStateOf(!HintPrefs.isDismissed(context, HINT_KEY_CARDS))
    }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<Long?>(null) }
    var tagFilterMenuFor by remember { mutableStateOf<Long?>(null) }
    var creatingTagFilter by remember { mutableStateOf(false) }
    var editingTagFilter by remember { mutableStateOf<Tag?>(null) }
    var deletingTagFilter by remember { mutableStateOf<Tag?>(null) }

    // Refreshed whenever the card list changes, and when returning from the
    // detail screen (tag edits there don't touch the `cards` Flow itself).
    var tagsByCard by remember { mutableStateOf<Map<Long, List<Tag>>>(emptyMap()) }
    LaunchedEffect(cards, draft) { tagsByCard = app.repository.getCardTagsByCard() }

    val searchFiltered = if (searchQuery.isBlank()) cards else cards.filter { card ->
        val query = searchQuery.trim()
        listOf(
            card.name, card.company, card.jobTitle, card.phone,
            card.email, card.website, card.address, card.notes,
        ).any { it.contains(query, ignoreCase = true) } ||
            tagsByCard[card.id].orEmpty().any { it.title.contains(query, ignoreCase = true) }
    }
    val filteredCards = tagFilter?.let { id ->
        searchFiltered.filter { card -> tagsByCard[card.id].orEmpty().any { it.id == id } }
    } ?: searchFiltered

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val imageUri = GmsDocumentScanningResult
            .fromActivityResultIntent(activityResult.data)
            ?.pages.orEmpty()
            .firstOrNull()?.imageUri
        if (imageUri != null) {
            parsing = true
            scope.launch {
                draft = runCatching { app.repository.parseCardImage(imageUri) }.getOrNull()
                parsing = false
                if (draft == null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.card_scan_failed)
                    )
                }
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { destination ->
        if (destination != null) scope.launch {
            val ok = app.repository.exportCardsCsv(cards, destination)
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.exported_contacts else R.string.export_failed)
            )
        }
    }

    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { destination ->
        if (destination != null) scope.launch {
            val ok = app.repository.exportCardsXlsx(cards, destination)
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.exported_contacts else R.string.export_failed)
            )
        }
    }

    val launchCardScanner: () -> Unit = {
        GmsDocumentScanning.getClient(cardScannerOptions)
            .getStartScanIntent(context as ComponentActivity)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
    }

    // Launched from the main screen's "Scan business card" button: go
    // straight into capture instead of landing on the list first.
    LaunchedEffect(Unit) {
        if (autoStartScan) launchCardScanner()
    }

    BackHandler(enabled = draft != null) { draft = null }

    if (draft != null) {
        CardDetailScreen(
            card = draft!!,
            onBack = { draft = null },
            onSave = { updated, pendingTagIds ->
                draft = null
                scope.launch {
                    val saved = app.repository.saveCard(updated)
                    // Tags picked before a brand-new card had a real id are
                    // applied now that saveCard has assigned one.
                    for (tagId in pendingTagIds) {
                        app.repository.toggleCardTag(saved.id, tagId, currentlyApplied = false)
                    }
                    tagsByCard = app.repository.getCardTagsByCard()
                }
            },
        )
        return
    }

    Scaffold(
        // A horizontal swipe in either direction goes back to Documents, one
        // screen over. Consumed only past a deliberate threshold so it can't
        // misfire from small drags/taps.
        modifier = Modifier.pointerInput(onBack) {
            val threshold = 120.dp.toPx()
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = {
                    if (!cardSelectionActive && abs(totalDrag) > threshold) onBack()
                },
                onDragCancel = { totalDrag = 0f },
            ) { change, dragAmount ->
                totalDrag += dragAmount
                change.consume()
            }
        },
        topBar = {
            if (cardSelectionActive) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedCardIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedCardIds.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                deletingCards = cards.filter { it.id in selectedCardIds }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete),
                                tint = DestructiveRed,
                            )
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { AppTitleWithIcon(stringResource(R.string.business_cards)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { exportMenuOpen = true },
                                enabled = cards.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    contentDescription = stringResource(R.string.export_contacts),
                                    tint = ShareBlue,
                                )
                            }
                            DropdownMenu(
                                expanded = exportMenuOpen,
                                onDismissRequest = { exportMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_csv)) },
                                    onClick = {
                                        exportMenuOpen = false
                                        csvLauncher.launch("ninja-scan-contacts.csv")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_excel)) },
                                    onClick = {
                                        exportMenuOpen = false
                                        xlsxLauncher.launch("ninja-scan-contacts.xlsx")
                                    },
                                )
                            }
                        }
                        ThemeToggleButton(isDarkTheme = isDarkTheme, onToggle = onToggleTheme)
                        DriveMenuButton(
                            enabled = driveBackupEnabled,
                            expanded = driveMenuOpen,
                            onExpandedChange = { driveMenuOpen = it },
                            onToggle = {
                                if (driveBackupEnabled) {
                                    DriveBackup.setEnabled(context, false)
                                    driveBackupEnabled = false
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.drive_backup_disabled)
                                        )
                                    }
                                } else {
                                    pendingDriveRestore = false
                                    requestDriveAuthorization()
                                }
                            },
                            onRestore = {
                                pendingDriveRestore = true
                                requestDriveAuthorization()
                            },
                            onBackupNow = {
                                DriveBackup.enqueue(context)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.drive_backup_started)
                                    )
                                }
                            },
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = onBack,
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_documents)) },
                    colors = brandedNavigationItemColors(),
                )
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_cards)) },
                    colors = brandedNavigationItemColors(),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = launchCardScanner,
                icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                text = { Text(stringResource(R.string.scan_business_card)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                restoreProgress != null -> DriveSyncProgressBar(
                    stringResource(R.string.drive_restore_started), restoreProgress!!
                )
                backupProgress != null -> DriveSyncProgressBar(
                    stringResource(R.string.drive_backup_started), backupProgress!!
                )
            }
            if (cards.isNotEmpty() || searchQuery.isNotBlank()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_cards_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.clear_search),
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (showLongPressHint && !cardSelectionActive && cards.isNotEmpty()) {
                LongPressHint(
                    text = stringResource(R.string.hint_long_press_cards),
                    onDismiss = {
                        showLongPressHint = false
                        HintPrefs.dismiss(context, HINT_KEY_CARDS)
                    },
                )
            }
            if (cards.isNotEmpty() || allTags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    item {
                        LongPressableChip(
                            label = stringResource(R.string.all_cards),
                            selected = tagFilter == null,
                            onClick = { tagFilter = null },
                        )
                    }
                    items(allTags, key = { it.id }) { tag ->
                        // Long-press a tag chip for rename/delete.
                        Box {
                            LongPressableChip(
                                label = tag.title,
                                selected = tagFilter == tag.id,
                                leadingDot = hexToColor(tag.color),
                                onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                                onLongClick = { tagFilterMenuFor = tag.id },
                            )
                            DropdownMenu(
                                expanded = tagFilterMenuFor == tag.id,
                                onDismissRequest = { tagFilterMenuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_tag)) },
                                    onClick = { tagFilterMenuFor = null; editingTagFilter = tag },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_tag)) },
                                    onClick = { tagFilterMenuFor = null; deletingTagFilter = tag },
                                )
                            }
                        }
                    }
                    item {
                        AssistChip(
                            onClick = { creatingTagFilter = true },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = ActionGreen,
                                )
                            },
                            label = { Text(stringResource(R.string.create_tag)) },
                        )
                    }
                }
            }
            when {
                parsing -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                filteredCards.isEmpty() && searchQuery.isNotBlank() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.no_card_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                cards.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContactPage,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.no_cards_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.no_cards_body),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom padding must clear the single FAB (56dp) plus
                    // Scaffold's own margin around it, or the last row hides
                    // behind it.
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredCards, key = { it.id }) { card ->
                        val cardTags = tagsByCard[card.id].orEmpty()
                        CardRow(
                            card = card,
                            tags = cardTags,
                            selectionMode = cardSelectionActive,
                            selected = card.id in selectedCardIds,
                            onOpen = { draft = card },
                            onToggleSelect = {
                                if (card.id in selectedCardIds) selectedCardIds.remove(card.id)
                                else selectedCardIds.add(card.id)
                            },
                            onSaveToContacts = { saveToContacts(context, card, cardTags) },
                            onDelete = { deleting = card },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_card_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_card_body,
                        card.name.ifBlank { card.company },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { app.repository.deleteCard(card) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingCards?.let { list ->
        AlertDialog(
            onDismissRequest = { deletingCards = null },
            title = { Text(stringResource(R.string.delete_cards_dialog_title)) },
            text = { Text(stringResource(R.string.delete_cards_dialog_body, list.size)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingCards = null
                    selectedCardIds.clear()
                    scope.launch { app.repository.deleteCards(list) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingCards = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (creatingTagFilter) {
        TagEditorDialog(
            existing = null,
            onDismiss = { creatingTagFilter = false },
            onSave = { title, description, color ->
                creatingTagFilter = false
                scope.launch { app.repository.createTag(title, description, color) }
            },
        )
    }

    editingTagFilter?.let { tag ->
        TagEditorDialog(
            existing = tag,
            onDismiss = { editingTagFilter = null },
            onSave = { title, description, color ->
                editingTagFilter = null
                scope.launch {
                    app.repository.updateTag(tag.copy(title = title, description = description, color = color))
                }
            },
        )
    }

    deletingTagFilter?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTagFilter = null },
            title = { Text(stringResource(R.string.delete_tag_title)) },
            text = { Text(stringResource(R.string.delete_tag_body, tag.title)) },
            confirmButton = {
                TextButton(onClick = {
                    if (tagFilter == tag.id) tagFilter = null
                    deletingTagFilter = null
                    scope.launch { app.repository.deleteTag(tag.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTagFilter = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    card: BusinessCard,
    tags: List<Tag>,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onSaveToContacts: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                    onLongClick = onToggleSelect,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Spacer(Modifier.width(8.dp))
            }
            val thumbnail = card.thumbnailPath?.let(::File)?.takeIf { it.exists() }
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    Icons.Filled.ContactPage,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.name.ifBlank { card.company }
                        .ifBlank { stringResource(R.string.business_cards) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                val subtitle = listOf(card.company, card.phone, card.email)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank() || card.photoDriveFileId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        if (card.photoDriveFileId != null) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.CloudDone,
                                contentDescription = stringResource(R.string.backed_up_to_drive),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                CardTagsRow(tags)
            }
            if (!selectionMode) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_to_contacts)) },
                        onClick = { menuOpen = false; onSaveToContacts() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

/** Small colored-dot + title row shown under a card's other details. */
@Composable
private fun CardTagsRow(tags: List<Tag>) {
    if (tags.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        tags.take(3).forEachIndexed { index, tag ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(hexToColor(tag.color)))
            Spacer(Modifier.width(4.dp))
            Text(
                tag.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun hexToColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

private fun colorToHex(color: Color): String =
    "#%06X".format(0xFFFFFF and color.toArgb())

/**
 * Full-page contact details editor, with the scanned card image at the
 * top and every field filling the width. Replaces the earlier dialog,
 * which cramped the fields into a small centered box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailScreen(
    card: BusinessCard,
    onBack: () -> Unit,
    onSave: (BusinessCard, List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScannerApp
    val scope = rememberCoroutineScope()

    var name by remember(card) { mutableStateOf(card.name) }
    var company by remember(card) { mutableStateOf(card.company) }
    var jobTitle by remember(card) { mutableStateOf(card.jobTitle) }
    var phone by remember(card) { mutableStateOf(card.phone) }
    var email by remember(card) { mutableStateOf(card.email) }
    var website by remember(card) { mutableStateOf(card.website) }
    var address by remember(card) { mutableStateOf(card.address) }
    var notes by remember(card) { mutableStateOf(card.notes) }

    // A brand-new (unsaved) card has id == 0L: tag choices are held locally
    // until `onSave` gives the caller a real id to link them to. For an
    // existing card, toggling writes straight through to the DB.
    val allTags by app.repository.tags.collectAsState(initial = emptyList())
    var appliedTagIds by remember(card.id) { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(card.id) {
        appliedTagIds = if (card.id == 0L) emptyList() else app.repository.getCardTags(card.id).map { it.id }
    }
    val cardTags = allTags.filter { it.id in appliedTagIds }
    var tagMenuOpen by remember { mutableStateOf(false) }
    var creatingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }

    fun toggleTag(tag: Tag) {
        val applied = tag.id in appliedTagIds
        if (card.id != 0L) {
            scope.launch { app.repository.toggleCardTag(card.id, tag.id, applied) }
        }
        appliedTagIds = if (applied) appliedTagIds - tag.id else appliedTagIds + tag.id
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(name.ifBlank { stringResource(R.string.business_cards) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        saveToContacts(
                            context,
                            card.copy(
                                name = name.trim(),
                                company = company.trim(),
                                jobTitle = jobTitle.trim(),
                                phone = phone.trim(),
                                email = email.trim(),
                                website = website.trim(),
                                address = address.trim(),
                                notes = notes.trim(),
                            ),
                            cardTags,
                        )
                    }) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = stringResource(R.string.save_to_contacts),
                            tint = ActionGreen,
                        )
                    }
                    TextButton(onClick = {
                        onSave(
                            card.copy(
                                name = name.trim(),
                                company = company.trim(),
                                jobTitle = jobTitle.trim(),
                                phone = phone.trim(),
                                email = email.trim(),
                                website = website.trim(),
                                address = address.trim(),
                                notes = notes.trim(),
                            ),
                            if (card.id == 0L) appliedTagIds else emptyList(),
                        )
                    }) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val thumbnail = card.thumbnailPath?.let(::File)?.takeIf { it.exists() }
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                cardTags.forEach { tag ->
                    LongPressableChip(
                        label = tag.title,
                        selected = true,
                        leadingDot = hexToColor(tag.color),
                        onClick = { toggleTag(tag) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Box {
                    AssistChip(
                        onClick = { tagMenuOpen = true },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = ActionGreen,
                            )
                        },
                        label = { Text(stringResource(R.string.add_tag)) },
                    )
                    DropdownMenu(expanded = tagMenuOpen, onDismissRequest = { tagMenuOpen = false }) {
                        allTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tag.title) },
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(hexToColor(tag.color))
                                    )
                                },
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            onClick = { tagMenuOpen = false; editingTag = tag },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = stringResource(R.string.edit_tag),
                                                modifier = Modifier.size(16.dp),
                                                tint = EditAmber,
                                            )
                                        }
                                        IconButton(
                                            onClick = { tagMenuOpen = false; deletingTag = tag },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.delete_tag),
                                                modifier = Modifier.size(16.dp),
                                                tint = DestructiveRed,
                                            )
                                        }
                                    }
                                },
                                onClick = { tagMenuOpen = false; toggleTag(tag) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.create_tag)) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, tint = ActionGreen) },
                            onClick = { tagMenuOpen = false; creatingTag = true },
                        )
                    }
                }
            }

            @Composable
            fun field(
                value: String,
                label: Int,
                onChange: (String) -> Unit,
                minLines: Int = 1,
                keyboardType: KeyboardType = KeyboardType.Text,
                trailingIcon: (@Composable () -> Unit)? = null,
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(stringResource(label)) },
                    singleLine = minLines == 1,
                    minLines = minLines,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    trailingIcon = trailingIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            field(name, R.string.field_name, { name = it })
            field(company, R.string.field_company, { company = it })
            field(jobTitle, R.string.field_job_title, { jobTitle = it })
            field(
                phone, R.string.field_phone, { phone = it },
                keyboardType = KeyboardType.Phone,
                trailingIcon = if (phone.isNotBlank()) {
                    {
                        Row {
                            IconButton(onClick = { openDialer(context, phone) }) {
                                Icon(
                                    Icons.Filled.Call,
                                    contentDescription = stringResource(R.string.call),
                                    tint = ActionGreen,
                                )
                            }
                            IconButton(onClick = { openWhatsApp(context, phone) }) {
                                Icon(
                                    Icons.Filled.Chat,
                                    contentDescription = stringResource(R.string.whatsapp),
                                    tint = WhatsAppGreen,
                                )
                            }
                        }
                    }
                } else null,
            )
            field(
                email, R.string.field_email, { email = it },
                keyboardType = KeyboardType.Email,
                trailingIcon = if (email.isNotBlank()) {
                    {
                        IconButton(onClick = { openEmail(context, email) }) {
                            Icon(
                                Icons.Filled.Email,
                                contentDescription = stringResource(R.string.send_email),
                                tint = EditAmber,
                            )
                        }
                    }
                } else null,
            )
            field(
                website, R.string.field_website, { website = it },
                keyboardType = KeyboardType.Uri,
                trailingIcon = if (website.isNotBlank()) {
                    {
                        IconButton(onClick = { openWebsite(context, website) }) {
                            Icon(
                                Icons.Filled.Language,
                                contentDescription = stringResource(R.string.open_website),
                                tint = ShareBlue,
                            )
                        }
                    }
                } else null,
            )
            field(
                address, R.string.field_address, { address = it }, minLines = 3,
                trailingIcon = if (address.isNotBlank()) {
                    {
                        IconButton(onClick = { openMap(context, address) }) {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = stringResource(R.string.open_map),
                                tint = DestructiveRed,
                            )
                        }
                    }
                } else null,
            )
            field(notes, R.string.field_notes, { notes = it }, minLines = 3)
        }
    }

    if (creatingTag) {
        TagEditorDialog(
            existing = null,
            onDismiss = { creatingTag = false },
            onSave = { title, description, color ->
                creatingTag = false
                scope.launch {
                    val tag = app.repository.createTag(title, description, color)
                    toggleTag(tag)
                }
            },
        )
    }

    editingTag?.let { tag ->
        TagEditorDialog(
            existing = tag,
            onDismiss = { editingTag = null },
            onSave = { title, description, color ->
                editingTag = null
                scope.launch {
                    app.repository.updateTag(tag.copy(title = title, description = description, color = color))
                }
            },
        )
    }

    deletingTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(stringResource(R.string.delete_tag_title)) },
            text = { Text(stringResource(R.string.delete_tag_body, tag.title)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingTag = null
                    scope.launch { app.repository.deleteTag(tag.id) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Opens the system "create contact" screen pre-filled from the card. */
private fun saveToContacts(context: Context, card: BusinessCard, tags: List<Tag>) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, card.name)
        putExtra(ContactsContract.Intents.Insert.COMPANY, card.company)
        putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.jobTitle)
        putExtra(ContactsContract.Intents.Insert.PHONE, card.phone)
        putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
        putExtra(ContactsContract.Intents.Insert.POSTAL, card.address)
        val tagsLabel = tags.takeIf { it.isNotEmpty() }
            ?.let { context.getString(R.string.contact_tags_label, it.joinToString(", ") { tag -> tag.title }) }
        val notes = listOfNotNull(card.notes.takeIf { it.isNotBlank() }, tagsLabel)
            .joinToString("\n")
        if (notes.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.NOTES, notes)
        }
        // Insert.WEBSITE doesn't exist on the basic extras above; a website
        // needs its own structured Data row, passed via Insert.DATA.
        if (card.website.isNotBlank()) {
            val websiteRow = ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, card.website)
                put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
            }
            putParcelableArrayListExtra(
                ContactsContract.Intents.Insert.DATA,
                arrayListOf(websiteRow),
            )
        }
    }
    runCatching { context.startActivity(intent) }
}

/** Strips visual formatting (spaces, dashes, parens) to a dialable "+"-prefixed number. */
private fun normalizePhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (phone.trimStart().startsWith("+")) "+$digits" else digits
}

/** Opens the system dialer pre-filled with the number (no CALL_PHONE permission needed). */
private fun openDialer(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(normalizePhone(phone))}"))
    runCatching { context.startActivity(intent) }
}

/** wa.me expects digits only (country code, no "+", spaces, or dashes). */
private fun openWhatsApp(context: Context, phone: String) {
    val digits = normalizePhone(phone).removePrefix("+")
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
    runCatching { context.startActivity(intent) }
}

/** EXTRA_EMAIL (rather than embedding the address in the URI) avoids encoding pitfalls. */
private fun openEmail(context: Context, email: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
    }
    runCatching { context.startActivity(intent) }
}

private fun openWebsite(context: Context, url: String) {
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        "https://$url"
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
    runCatching { context.startActivity(intent) }
}

/** Tries a maps app first (geo: URI), falling back to a Maps web search. */
private fun openMap(context: Context, address: String) {
    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}"))
    val opened = runCatching { context.startActivity(geoIntent) }.isSuccess
    if (!opened) {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(address)}"),
        )
        runCatching { context.startActivity(webIntent) }
    }
}

/** Create-or-edit dialog: title/confirm label switch on whether [existing] is null. */
@Composable
private fun TagEditorDialog(
    existing: Tag?,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, color: String) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    val palette = listOf(
        colorResource(R.color.tag_red), colorResource(R.color.tag_orange),
        colorResource(R.color.tag_amber), colorResource(R.color.tag_green),
        colorResource(R.color.tag_mint), colorResource(R.color.tag_blue),
        colorResource(R.color.tag_light_blue), colorResource(R.color.tag_purple),
        colorResource(R.color.tag_lavender), colorResource(R.color.tag_pink),
        Color.Black,
    )
    var selectedIndex by remember {
        mutableStateOf(
            existing?.color
                ?.let { hex -> palette.indexOfFirst { colorToHex(it).equals(hex, ignoreCase = true) } }
                ?.takeIf { it >= 0 }
                ?: (palette.size - 1)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.create_tag else R.string.edit_tag)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.tag_title)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.tag_description)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Column(Modifier.padding(top = 12.dp)) {
                    palette.chunked(6).forEach { rowColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            rowColors.forEach { swatch ->
                                val index = palette.indexOf(swatch)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .clickable { selectedIndex = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (index == selectedIndex) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), description.trim(), colorToHex(palette[selectedIndex])) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(if (existing == null) R.string.create else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
