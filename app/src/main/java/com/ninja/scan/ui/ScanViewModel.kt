package com.ninja.scan.ui

import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ninja.scan.DocScannerApp
import com.ninja.scan.data.ScanDocument
import com.ninja.scan.data.ScanRepository
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ScanEvent {
    data class Saved(val readableSize: String) : ScanEvent
    data class Error(val message: String) : ScanEvent
    data object Exported : ScanEvent
    data object ExportFailed : ScanEvent
    data object DriveBackupEnabled : ScanEvent
    data object DriveBackupDisabled : ScanEvent
    data class DriveBackupFailed(val message: String) : ScanEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModel(private val repository: ScanRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _folderFilter = MutableStateFlow<String?>(null)
    val folderFilter: StateFlow<String?> = _folderFilter

    /** All folder names currently in use, for the filter chips and dialogs. */
    val folders: StateFlow<List<String>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Library contents; narrowed by search text and the selected folder. */
    val scans: StateFlow<List<ScanDocument>> = _searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) repository.scans else repository.search(query.trim())
        }
        .combine(_folderFilter) { list, folder ->
            if (folder == null) list else list.filter { it.folder == folder }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFolderFilterChange(folder: String?) {
        _folderFilter.value = folder
    }

    fun moveToFolder(scan: ScanDocument, folder: String?) {
        viewModelScope.launch { repository.moveToFolder(scan, folder) }
    }

    private val _driveBackupEnabled = MutableStateFlow(false)
    val driveBackupEnabled: StateFlow<Boolean> = _driveBackupEnabled

    fun setDriveBackupState(enabled: Boolean) {
        _driveBackupEnabled.value = enabled
    }

    fun emitEvent(event: ScanEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    private val _events = MutableSharedFlow<ScanEvent>()
    val events: SharedFlow<ScanEvent> = _events

    /** Scan pending a SAF "create document" destination pick. */
    var pendingExport: ScanDocument? = null
        private set

    /** The scan just saved, pending the name-and-organize dialog. */
    private val _justSaved = MutableStateFlow<ScanDocument?>(null)
    val justSaved: StateFlow<ScanDocument?> = _justSaved

    fun onScanResult(result: GmsDocumentScanningResult?, app: DocScannerApp) {
        if (result == null) return
        viewModelScope.launch {
            runCatching { repository.saveScan(result) }
                .onSuccess { scan ->
                    _events.emit(
                        ScanEvent.Saved(Formatter.formatShortFileSize(app, scan.sizeBytes))
                    )
                    _justSaved.value = scan
                    if (com.ninja.scan.drive.DriveBackup.isEnabled(app)) {
                        com.ninja.scan.drive.DriveBackup.enqueue(app)
                    }
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
        }
    }

    fun dismissScanDetails() {
        _justSaved.value = null
    }

    fun confirmScanDetails(scan: ScanDocument, title: String, folder: String?) {
        _justSaved.value = null
        viewModelScope.launch {
            repository.rename(scan, title)
            repository.moveToFolder(scan, folder)
        }
    }

    fun onScanError(message: String) {
        viewModelScope.launch { _events.emit(ScanEvent.Error(message)) }
    }

    fun requestExport(scan: ScanDocument) {
        pendingExport = scan
    }

    fun onExportDestination(destination: android.net.Uri?) {
        val scan = pendingExport ?: return
        pendingExport = null
        if (destination == null) return
        viewModelScope.launch {
            val ok = repository.exportTo(scan, destination)
            _events.emit(if (ok) ScanEvent.Exported else ScanEvent.ExportFailed)
        }
    }

    fun rename(scan: ScanDocument, title: String) {
        viewModelScope.launch { repository.rename(scan, title) }
    }

    fun delete(scan: ScanDocument) {
        viewModelScope.launch { repository.delete(scan) }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as DocScannerApp
                return ScanViewModel(app.repository) as T
            }
        }
    }
}
