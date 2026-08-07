package com.ninja.scan.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ninja.scan.DocScannerApp
import com.ninja.scan.data.DocumentTag
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
    data object DriveBackupStarted : ScanEvent
    data class DriveBackupFailed(val message: String) : ScanEvent
    data class DriveBackupCompleted(val scans: Int, val cards: Int) : ScanEvent
    data class DriveBackupIncomplete(val failures: Int) : ScanEvent
    data object DriveBackupNeedsRecoveryKey : ScanEvent
    data object DriveRestoreStarted : ScanEvent
    data class DriveRestoreCompleted(val scans: Int, val cards: Int) : ScanEvent
    data object DriveRestoreFailed : ScanEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModel(private val repository: ScanRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _tagFilter = MutableStateFlow<Long?>(null)
    val tagFilter: StateFlow<Long?> = _tagFilter

    /** All document tags currently in use, for the filter chips and dialogs. */
    val documentTags: StateFlow<List<DocumentTag>> = repository.documentTags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** scanId -> its tags, so rows and the filter can stay live. */
    val scanTagsByScan: StateFlow<Map<Long, List<DocumentTag>>> = repository.scanTagsByScan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Library contents; narrowed by search text and the selected tag. */
    val scans: StateFlow<List<ScanDocument>> = combine(
        _searchQuery.flatMapLatest { query ->
            if (query.isBlank()) repository.scans else repository.search(query.trim())
        },
        _tagFilter,
        scanTagsByScan,
    ) { list, tagId, tagsByScan ->
        if (tagId == null) list else list.filter { scan -> tagsByScan[scan.id].orEmpty().any { it.id == tagId } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onTagFilterChange(tagId: Long?) {
        _tagFilter.value = tagId
    }

    fun toggleScanTag(scan: ScanDocument, tag: DocumentTag, currentlyApplied: Boolean) {
        viewModelScope.launch { repository.toggleScanTag(scan.id, tag.id, currentlyApplied) }
    }

    fun createDocumentTag(title: String, description: String, color: String) {
        viewModelScope.launch { repository.createDocumentTag(title, description, color) }
    }

    fun updateDocumentTag(tag: DocumentTag) {
        viewModelScope.launch { repository.updateDocumentTag(tag) }
    }

    fun deleteDocumentTag(tagId: Long) {
        viewModelScope.launch {
            repository.deleteDocumentTag(tagId)
            if (_tagFilter.value == tagId) _tagFilter.value = null
        }
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
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
        }
    }

    fun onIdCardScanResult(result: GmsDocumentScanningResult?, app: DocScannerApp) {
        if (result == null) return
        viewModelScope.launch {
            runCatching { repository.saveIdCardScan(result) }
                .onSuccess { scan ->
                    _events.emit(
                        ScanEvent.Saved(Formatter.formatShortFileSize(app, scan.sizeBytes))
                    )
                    _justSaved.value = scan
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
        }
    }

    fun onImportPdf(uri: Uri?, app: DocScannerApp) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching { repository.importPdf(uri) }
                .onSuccess { scan ->
                    _events.emit(
                        ScanEvent.Saved(Formatter.formatShortFileSize(app, scan.sizeBytes))
                    )
                    _justSaved.value = scan
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
        }
    }

    fun onImportImages(uris: List<Uri>, app: DocScannerApp) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.saveImportedImages(uris) }
                .onSuccess { scan ->
                    _events.emit(
                        ScanEvent.Saved(Formatter.formatShortFileSize(app, scan.sizeBytes))
                    )
                    _justSaved.value = scan
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
        }
    }

    fun dismissScanDetails() {
        _justSaved.value = null
    }

    fun confirmScanDetails(scan: ScanDocument, title: String, tagIds: List<Long>) {
        _justSaved.value = null
        viewModelScope.launch {
            repository.rename(scan, title)
            // A freshly saved scan starts with zero tags, so every id here is
            // a straight "add" — never a toggle-off.
            for (tagId in tagIds) repository.toggleScanTag(scan.id, tagId, false)
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

    fun deleteScans(scans: List<ScanDocument>) {
        viewModelScope.launch { repository.deleteScans(scans) }
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
