package com.ninja.scan.util

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ninja.scan.DocScannerApp
import com.ninja.scan.R
import com.ninja.scan.data.ScanDocument
import com.ninja.scan.data.ScanRepository
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

    /** Shares just the given (0-based) page indices as one combined PDF. */
    fun sharePagesPdf(activity: ComponentActivity, scan: ScanDocument, pageIndices: List<Int>) {
        launchShare(activity, scan) { repository ->
            listOf(repository.preparePagesPdf(scan, pageIndices)) to "application/pdf"
        }
    }

    /** Shares just the given (0-based) page indices, each as its own image. */
    fun sharePageImages(activity: ComponentActivity, scan: ScanDocument, pageIndices: List<Int>) {
        launchShare(activity, scan) { repository ->
            repository.preparePageImages(scan, pageIndices) to "image/jpeg"
        }
    }

    fun sharePdfs(activity: ComponentActivity, scans: List<ScanDocument>) {
        launchShareMulti(activity, scans) { repository, scan ->
            listOf(repository.preparePdfForSharing(scan)) to "application/pdf"
        }
    }

    fun shareImagesMulti(activity: ComponentActivity, scans: List<ScanDocument>) {
        launchShareMulti(activity, scans) { repository, scan ->
            repository.preparePageImages(scan) to "image/jpeg"
        }
    }

    fun shareLongImageMulti(activity: ComponentActivity, scans: List<ScanDocument>) {
        launchShareMulti(activity, scans) { repository, scan ->
            listOf(repository.prepareLongImage(scan)) to "image/jpeg"
        }
    }

    fun shareSeparatePdfsMulti(activity: ComponentActivity, scans: List<ScanDocument>) {
        launchShareMulti(activity, scans) { repository, scan ->
            repository.prepareSeparatePdfs(scan) to "application/pdf"
        }
    }

    private inline fun launchShare(
        activity: ComponentActivity,
        scan: ScanDocument,
        crossinline prepare: suspend (ScanRepository) -> Pair<List<File>, String>,
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
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_caption))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, scan.title))
        }
    }

    /**
     * Same as [launchShare] but prepares every scan's files (best-effort per
     * scan — one failure doesn't block the others) and shares them together.
     */
    private inline fun launchShareMulti(
        activity: ComponentActivity,
        scans: List<ScanDocument>,
        crossinline prepare: suspend (ScanRepository, ScanDocument) -> Pair<List<File>, String>,
    ) {
        val repository = (activity.application as DocScannerApp).repository
        activity.lifecycleScope.launch {
            var mimeType = "application/pdf"
            val files = scans.flatMap { scan ->
                runCatching { prepare(repository, scan) }.getOrNull()?.let { (scanFiles, mime) ->
                    mimeType = mime
                    scanFiles
                }.orEmpty()
            }
            if (files.isEmpty()) return@launch
            val uris = ArrayList(files.map { contentUri(activity, it) })
            val subject = if (scans.size == 1) {
                scans.first().title
            } else {
                activity.getString(R.string.share_multiple_subject, scans.size)
            }
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
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.share_caption))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, subject))
        }
    }

    private fun contentUri(activity: ComponentActivity, file: File): Uri =
        FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
}
