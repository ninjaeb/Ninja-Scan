package com.ninja.scan.util

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ninja.scan.DocScannerApp
import com.ninja.scan.data.ScanDocument
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Prints a scanned PDF (watermarked when set) through Android's native print
 * framework, handing the print subsystem the same already-valid PDF file
 * used for sharing/exporting rather than re-rendering pages ourselves.
 */
object PrintActions {

    fun printPdf(activity: ComponentActivity, scan: ScanDocument) {
        val repository = (activity.application as DocScannerApp).repository
        activity.lifecycleScope.launch {
            val file = runCatching { repository.preparePdfForSharing(scan) }.getOrNull()
                ?: return@launch
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(scan.title, PdfPrintDocumentAdapter(file, scan.title), null)
        }
    }

    private class PdfPrintDocumentAdapter(
        private val file: File,
        private val label: String,
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(label)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
