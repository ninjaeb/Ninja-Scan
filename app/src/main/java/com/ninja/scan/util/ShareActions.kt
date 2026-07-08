package com.ninja.scan.util

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ninja.scan.DocScannerApp
import com.ninja.scan.data.ScanDocument
import kotlinx.coroutines.launch
import java.io.File

/**
 * Share intents for scanned documents, usable from any activity (library
 * list and PDF viewer). File preparation (watermarking, rendering) happens
 * off the main thread via the repository before the chooser opens.
 */
object ShareActions {

    fun sharePdf(activity: ComponentActivity, scan: ScanDocument) {
        launchShare(activity, scan) { repository ->
            listOf(repository.preparePdfForSharing(scan)) to "application/pdf"
        }
    }

    fun shareImages(activity: ComponentActivity, scan: ScanDocument) {
        launchShare(activity, scan) { repository ->
            repository.preparePageImages(scan) to "image/jpeg"
        }
    }

    fun shareLongImage(activity: ComponentActivity, scan: ScanDocument) {
        launchShare(activity, scan) { repository ->
            listOf(repository.prepareLongImage(scan)) to "image/jpeg"
        }
    }

    fun shareSeparatePdfs(activity: ComponentActivity, scan: ScanDocument) {
        launchShare(activity, scan) { repository ->
            repository.prepareSeparatePdfs(scan) to "application/pdf"
        }
    }

    private inline fun launchShare(
        activity: ComponentActivity,
        scan: ScanDocument,
        crossinline prepare: suspend (com.ninja.scan.data.ScanRepository) -> Pair<List<File>, String>,
    ) {
        val repository = (activity.application as DocScannerApp).repository
        activity.lifecycleScope.launch {
            val (files, mimeType) = runCatching { prepare(repository) }.getOrNull()
                ?: return@launch
            if (files.isEmpty()) return@launch
            val uris = ArrayList(files.map { contentUri(activity, it) })
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }.apply {
                putExtra(Intent.EXTRA_SUBJECT, scan.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, scan.title))
        }
    }

    private fun contentUri(activity: ComponentActivity, file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
}
