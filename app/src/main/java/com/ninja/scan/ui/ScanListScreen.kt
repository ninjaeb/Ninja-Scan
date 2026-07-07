package com.ninja.scan.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninja.scan.R
import com.ninja.scan.data.ScanDocument
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanListScreen(
    scans: List<ScanDocument>,
    searchQuery: String,
    folders: List<String>,
    folderFilter: String?,
    driveBackupEnabled: Boolean,
    justSaved: ScanDocument?,
    snackbarHostState: SnackbarHostState,
    onConfirmScanDetails: (ScanDocument, String, String?) -> Unit,
    onDismissScanDetails: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFolderFilterChange: (String?) -> Unit,
    onToggleDriveBackup: () -> Unit,
    onOpenCards: () -> Unit,
    onScanCardClick: () -> Unit,
    onScanClick: () -> Unit,
    onOpen: (ScanDocument) -> Unit,
    onOpenWith: (ScanDocument) -> Unit,
    onEdit: (ScanDocument) -> Unit,
    onShare: (ScanDocument) -> Unit,
    onShareAsImages: (ScanDocument) -> Unit,
    onSaveToCloud: (ScanDocument) -> Unit,
    onRename: (ScanDocument, String) -> Unit,
    onMoveToFolder: (ScanDocument, String?) -> Unit,
    onDelete: (ScanDocument) -> Unit,
) {
    justSaved?.let { scan ->
        SaveDetailsDialog(
            scan = scan,
            folders = folders,
            onConfirm = { title, folder -> onConfirmScanDetails(scan, title, folder) },
            onDismiss = onDismissScanDetails,
        )
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenCards) {
                        Icon(
                            Icons.Filled.ContactPage,
                            contentDescription = stringResource(R.string.business_cards),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onToggleDriveBackup) {
                        Icon(
                            if (driveBackupEnabled) Icons.Filled.CloudDone
                            else Icons.Filled.CloudOff,
                            contentDescription = stringResource(R.string.drive_backup),
                            tint = if (driveBackupEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Secondary action: document scanning
                ExtendedFloatingActionButton(
                    onClick = onScanClick,
                    icon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.scan_document)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(12.dp))
                // Primary, most prominent action: business card scanning
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
            if (scans.isNotEmpty() || searching || folderFilter != null) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (folders.isNotEmpty()) {
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
                        FilterChip(
                            selected = folderFilter == folder,
                            onClick = {
                                onFolderFilterChange(
                                    if (folderFilter == folder) null else folder
                                )
                            },
                            label = { Text(folder) },
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scans, key = { it.id }) { scan ->
                        ScanRow(
                            scan = scan,
                            folders = folders,
                            onOpen = { onOpen(scan) },
                            onOpenWith = { onOpenWith(scan) },
                            onEdit = { onEdit(scan) },
                            onShare = { onShare(scan) },
                            onShareAsImages = { onShareAsImages(scan) },
                            onSaveToCloud = { onSaveToCloud(scan) },
                            onRename = { onRename(scan, it) },
                            onMoveToFolder = { onMoveToFolder(scan, it) },
                            onDelete = { onDelete(scan) },
                        )
                    }
                }
            }
        }
    }
}

/** Shown right after a scan is saved: set the name and pick a folder. */
@Composable
private fun SaveDetailsDialog(
    scan: ScanDocument,
    folders: List<String>,
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
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            selectedFolder = if (selectedFolder == folder) null else folder
                            newFolder = ""
                        }) {
                            Text(
                                folder,
                                color = if (folder == selectedFolder) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
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

@Composable
private fun ScanRow(
    scan: ScanDocument,
    folders: List<String>,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onShareAsImages: () -> Unit,
    onSaveToCloud: () -> Unit,
    onRename: (String) -> Unit,
    onMoveToFolder: (String?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var movingToFolder by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open)) },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_with)) },
                        onClick = { menuOpen = false; onOpenWith() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_pages)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_folder)) },
                        onClick = { menuOpen = false; movingToFolder = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_as_images)) },
                        onClick = { menuOpen = false; onShareAsImages() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_to_cloud)) },
                        onClick = { menuOpen = false; onSaveToCloud() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; confirmingDelete = true },
                    )
                }
            }
        }
    }

    if (renaming) {
        var title by remember(scan.id) { mutableStateOf(scan.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(title) }) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (movingToFolder) {
        var newFolder by remember(scan.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { movingToFolder = false },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    folders.forEach { folder ->
                        androidx.compose.material3.TextButton(
                            onClick = { movingToFolder = false; onMoveToFolder(folder) },
                        ) {
                            Text(
                                folder,
                                color = if (folder == scan.folder) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    if (scan.folder != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { movingToFolder = false; onMoveToFolder(null) },
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
                        movingToFolder = false
                        onMoveToFolder(newFolder.trim())
                    },
                    enabled = newFolder.isNotBlank(),
                ) {
                    Text(stringResource(R.string.move))
                }
            },
            dismissButton = {
                TextButton(onClick = { movingToFolder = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_body, scan.title)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
