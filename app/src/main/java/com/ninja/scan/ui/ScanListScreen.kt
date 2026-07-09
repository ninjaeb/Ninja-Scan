package com.ninja.scan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninja.scan.R
import com.ninja.scan.data.ScanDocument
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanListScreen(
    scans: List<ScanDocument>,
    searchQuery: String,
    folders: List<String>,
    folderColors: Map<String, String>,
    folderFilter: String?,
    driveBackupEnabled: Boolean,
    justSaved: ScanDocument?,
    snackbarHostState: SnackbarHostState,
    restoreProgress: SyncProgress?,
    backupProgress: SyncProgress?,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onConfirmScanDetails: (ScanDocument, String, String?) -> Unit,
    onDismissScanDetails: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFolderFilterChange: (String?) -> Unit,
    onToggleDriveBackup: () -> Unit,
    onOpenCards: () -> Unit,
    onScanCardClick: () -> Unit,
    onScanClick: () -> Unit,
    onOpen: (ScanDocument) -> Unit,
    onSharePdf: (ScanDocument) -> Unit,
    onShareImages: (ScanDocument) -> Unit,
    onShareLongImage: (ScanDocument) -> Unit,
    onShareSeparatePdfs: (ScanDocument) -> Unit,
    onSharePdfs: (List<ScanDocument>) -> Unit,
    onShareImagesMulti: (List<ScanDocument>) -> Unit,
    onShareLongImageMulti: (List<ScanDocument>) -> Unit,
    onShareSeparatePdfsMulti: (List<ScanDocument>) -> Unit,
    onSaveToCloud: (ScanDocument) -> Unit,
    onRename: (ScanDocument, String) -> Unit,
    onMoveToFolder: (ScanDocument, String?) -> Unit,
    onDelete: (ScanDocument) -> Unit,
    onDeleteScans: (List<ScanDocument>) -> Unit,
    onAddFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onRestoreFromDrive: () -> Unit,
    onBackupNowDrive: () -> Unit,
) {
    justSaved?.let { scan ->
        SaveDetailsDialog(
            scan = scan,
            folders = folders,
            folderColors = folderColors,
            onConfirm = { title, folder -> onConfirmScanDetails(scan, title, folder) },
            onDismiss = onDismissScanDetails,
        )
    }

    // Row-menu actions drive the share-format sheet and these dialogs.
    var sharingScan by remember { mutableStateOf<ScanDocument?>(null) }
    var renamingScan by remember { mutableStateOf<ScanDocument?>(null) }
    var movingScan by remember { mutableStateOf<ScanDocument?>(null) }
    var deletingScan by remember { mutableStateOf<ScanDocument?>(null) }

    // Folder management (add chip + long-press menu on a folder chip).
    var addingFolder by remember { mutableStateOf(false) }
    var folderMenuFor by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var deletingFolder by remember { mutableStateOf<String?>(null) }
    var driveMenuOpen by remember { mutableStateOf(false) }

    // Long-press a row to pick several scans and share them together.
    val selectedIds = remember { mutableStateListOf<Long>() }
    val selectionActive = selectedIds.isNotEmpty()
    var multiSharingScans by remember { mutableStateOf<List<ScanDocument>?>(null) }
    var deletingScans by remember { mutableStateOf<List<ScanDocument>?>(null) }
    BackHandler(enabled = selectionActive) { selectedIds.clear() }

    Scaffold(
        topBar = {
            if (selectionActive) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                multiSharingScans = scans.filter { it.id in selectedIds }
                            },
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(
                            onClick = {
                                deletingScans = scans.filter { it.id in selectedIds }
                            },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { AppTitleWithIcon(stringResource(R.string.app_name)) },
                    actions = {
                        ThemeToggleButton(isDarkTheme = isDarkTheme, onToggle = onToggleTheme)
                        DriveMenuButton(
                            enabled = driveBackupEnabled,
                            expanded = driveMenuOpen,
                            onExpandedChange = { driveMenuOpen = it },
                            onToggle = onToggleDriveBackup,
                            onRestore = onRestoreFromDrive,
                            onBackupNow = onBackupNowDrive,
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_documents)) },
                    colors = brandedNavigationItemColors(),
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenCards,
                    icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_cards)) },
                    colors = brandedNavigationItemColors(),
                )
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Both scan actions share the same brand color; only their
                // stacking order signals which is primary.
                ExtendedFloatingActionButton(
                    onClick = onScanClick,
                    icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.scan_document)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onScanCardClick,
                    icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                    text = { Text(stringResource(R.string.scan_business_card)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        val searching = searchQuery.isNotBlank()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                restoreProgress != null -> DriveSyncProgressBar(
                    stringResource(R.string.drive_restore_started), restoreProgress
                )
                backupProgress != null -> DriveSyncProgressBar(
                    stringResource(R.string.drive_backup_started), backupProgress
                )
            }
            if (scans.isNotEmpty() || searching || folderFilter != null) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (scans.isNotEmpty() || folders.isNotEmpty() || folderFilter != null) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = folderFilter == null,
                            onClick = { onFolderFilterChange(null) },
                            label = { Text(stringResource(R.string.all_scans)) },
                        )
                    }
                    items(folders) { folder ->
                        // Long-press a folder chip for rename/delete.
                        Box {
                            LongPressableChip(
                                label = folder,
                                selected = folderFilter == folder,
                                leadingDot = folderColors[folder]?.let(::hexToColor),
                                onClick = {
                                    onFolderFilterChange(
                                        if (folderFilter == folder) null else folder
                                    )
                                },
                                onLongClick = { folderMenuFor = folder },
                            )
                            DropdownMenu(
                                expanded = folderMenuFor == folder,
                                onDismissRequest = { folderMenuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename_folder)) },
                                    onClick = { folderMenuFor = null; renamingFolder = folder },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_folder)) },
                                    onClick = { folderMenuFor = null; deletingFolder = folder },
                                )
                            }
                        }
                    }
                    item {
                        AssistChip(
                            onClick = { addingFolder = true },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text(stringResource(R.string.add_folder)) },
                        )
                    }
                }
            }
            when {
                scans.isEmpty() && searching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                scans.isEmpty() -> EmptyLibrary()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom padding must clear the two stacked FABs (56dp
                    // each + 12dp spacer between = 124dp) plus Scaffold's own
                    // margin around them, or the last row hides behind them.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 172.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scans, key = { it.id }) { scan ->
                        ScanRow(
                            scan = scan,
                            folderColors = folderColors,
                            selectionMode = selectionActive,
                            selected = scan.id in selectedIds,
                            onClick = { onOpen(scan) },
                            onToggleSelect = {
                                if (scan.id in selectedIds) selectedIds.remove(scan.id)
                                else selectedIds.add(scan.id)
                            },
                            onShare = { sharingScan = scan },
                            onRename = { renamingScan = scan },
                            onDelete = { deletingScan = scan },
                            onSaveToCloud = { onSaveToCloud(scan) },
                            onMoveToFolder = { movingScan = scan },
                        )
                    }
                }
            }
        }
    }

    sharingScan?.let { scan ->
        ShareFormatSheet(
            scan = scan,
            onDismiss = { sharingScan = null },
            onPdf = { sharingScan = null; onSharePdf(scan) },
            onImages = { sharingScan = null; onShareImages(scan) },
            onLongImage = { sharingScan = null; onShareLongImage(scan) },
            onSeparatePdfs = { sharingScan = null; onShareSeparatePdfs(scan) },
        )
    }

    multiSharingScans?.let { list ->
        ShareFormatSheet(
            scans = list,
            onDismiss = { multiSharingScans = null },
            onPdf = { multiSharingScans = null; selectedIds.clear(); onSharePdfs(list) },
            onImages = { multiSharingScans = null; selectedIds.clear(); onShareImagesMulti(list) },
            onLongImage = {
                multiSharingScans = null; selectedIds.clear(); onShareLongImageMulti(list)
            },
            onSeparatePdfs = {
                multiSharingScans = null; selectedIds.clear(); onShareSeparatePdfsMulti(list)
            },
        )
    }

    renamingScan?.let { scan ->
        var title by remember(scan.id) { mutableStateOf(scan.title) }
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { renamingScan = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = { renamingScan = null; onRename(scan, title) }) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingScan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    movingScan?.let { scan ->
        var newFolder by remember(scan.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { movingScan = null },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        folders.forEach { folder ->
                            LongPressableChip(
                                label = folder,
                                selected = folder == scan.folder,
                                leadingDot = folderColors[folder]?.let(::hexToColor),
                                onClick = { movingScan = null; onMoveToFolder(scan, folder) },
                            )
                        }
                    }
                    if (scan.folder != null) {
                        TextButton(
                            onClick = { movingScan = null; onMoveToFolder(scan, null) },
                        ) {
                            Text(stringResource(R.string.remove_from_folder))
                        }
                    }
                    OutlinedTextField(
                        value = newFolder,
                        onValueChange = { newFolder = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.new_folder_hint)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        movingScan = null
                        onMoveToFolder(scan, newFolder.trim())
                    },
                    enabled = newFolder.isNotBlank(),
                ) {
                    Text(stringResource(R.string.move))
                }
            },
            dismissButton = {
                TextButton(onClick = { movingScan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingScan?.let { scan ->
        AlertDialog(
            onDismissRequest = { deletingScan = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_body, scan.title)) },
            confirmButton = {
                TextButton(onClick = { deletingScan = null; onDelete(scan) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingScan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingScans?.let { list ->
        AlertDialog(
            onDismissRequest = { deletingScans = null },
            title = { Text(stringResource(R.string.delete_scans_dialog_title)) },
            text = { Text(stringResource(R.string.delete_scans_dialog_body, list.size)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingScans = null
                    selectedIds.clear()
                    onDeleteScans(list)
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingScans = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (addingFolder) {
        var name by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { addingFolder = false },
            title = { Text(stringResource(R.string.add_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.new_folder_hint)) },
                    modifier = Modifier.focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            },
            confirmButton = {
                TextButton(
                    onClick = { addingFolder = false; onAddFolder(name.trim()) },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { addingFolder = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    renamingFolder?.let { folder ->
        var name by remember(folder) { mutableStateOf(folder) }
        val trimmed = name.trim()
        val collides = trimmed != folder && folders.contains(trimmed)
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text(stringResource(R.string.rename_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    if (collides) {
                        Text(
                            stringResource(R.string.folder_merge_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { renamingFolder = null; onRenameFolder(folder, trimmed) },
                    enabled = trimmed.isNotEmpty() && trimmed != folder,
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingFolder = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text(stringResource(R.string.delete_folder_title)) },
            text = { Text(stringResource(R.string.delete_folder_body, folder)) },
            confirmButton = {
                TextButton(onClick = { deletingFolder = null; onDeleteFolder(folder) }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingFolder = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}


/** Shown right after a scan is saved: set the name and pick a folder. */
@Composable
private fun SaveDetailsDialog(
    scan: ScanDocument,
    folders: List<String>,
    folderColors: Map<String, String>,
    onConfirm: (title: String, folder: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(scan.id) { mutableStateOf(scan.title) }
    var selectedFolder by remember(scan.id) { mutableStateOf(scan.folder) }
    var newFolder by remember(scan.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_details_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_name)) },
                )
                if (folders.isNotEmpty()) {
                    Text(
                        stringResource(R.string.save_details_folder),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        folders.forEach { folder ->
                            LongPressableChip(
                                label = folder,
                                selected = folder == selectedFolder,
                                leadingDot = folderColors[folder]?.let(::hexToColor),
                                onClick = {
                                    selectedFolder = if (selectedFolder == folder) null else folder
                                    newFolder = ""
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it; if (it.isNotBlank()) selectedFolder = null },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.new_folder_hint)) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(title, newFolder.trim().ifEmpty { selectedFolder })
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.skip))
            }
        },
    )
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.DocumentScanner,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.empty_library_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.empty_library_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanRow(
    scan: ScanDocument,
    folderColors: Map<String, String>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onSaveToCloud: () -> Unit,
    onMoveToFolder: () -> Unit,
) {
    val context = LocalContext.current
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
                    onClick = { if (selectionMode) onToggleSelect() else onClick() },
                    onLongClick = onToggleSelect,
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                Spacer(Modifier.width(8.dp))
            }
            val thumbnail = scan.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
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
                    Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    scan.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                val pages =
                    if (scan.pageCount == 1) stringResource(R.string.page)
                    else stringResource(R.string.pages, scan.pageCount)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$pages · ${Formatter.formatShortFileSize(context, scan.sizeBytes)} · " +
                            DateUtils.getRelativeTimeSpanString(scan.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (scan.driveFileId != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.CloudDone,
                            contentDescription = stringResource(R.string.backed_up_to_drive),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                scan.folder?.let { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(folderColors[folder]?.let(::hexToColor) ?: Color.Gray)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            folder,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (!selectionMode) Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_to_cloud)) },
                        onClick = { menuOpen = false; onSaveToCloud() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_folder)) },
                        onClick = { menuOpen = false; onMoveToFolder() },
                    )
                }
            }
        }
    }
}
