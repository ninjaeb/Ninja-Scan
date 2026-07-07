package com.eugeneboon.docscanner.ui

import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.eugeneboon.docscanner.DocScannerApp
import com.eugeneboon.docscanner.data.ScanDocument
import com.eugeneboon.docscanner.data.ScanRepository
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ScanEvent {
    data class Saved(val readableSize: String) : ScanEvent
    data class Error(val message: String) : ScanEvent
    data object Exported : ScanEvent
    data object ExportFailed : ScanEvent
}

class ScanViewModel(private val repository: ScanRepository) : ViewModel() {

    val scans: StateFlow<List<ScanDocument>> = repository.scans
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<ScanEvent>()
    val events: SharedFlow<ScanEvent> = _events

    /** Scan pending a SAF "create document" destination pick. */
    var pendingExport: ScanDocument? = null
        private set

    fun onScanResult(result: GmsDocumentScanningResult?, app: DocScannerApp) {
        if (result == null) return
        viewModelScope.launch {
            runCatching { repository.saveScan(result) }
                .onSuccess { scan ->
                    _events.emit(
                        ScanEvent.Saved(Formatter.formatShortFileSize(app, scan.sizeBytes))
                    )
                }
                .onFailure { _events.emit(ScanEvent.Error(it.message ?: "unknown error")) }
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
