package com.eugeneboon.docscanner.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.eugeneboon.docscanner.ui.theme.DocScannerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Displays a scanned PDF in-app using the platform PdfRenderer. */
class PdfViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val watermark = intent.getStringExtra(EXTRA_WATERMARK)
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            finish()
            return
        }
        setContent {
            DocScannerTheme {
                PdfViewerScreen(
                    file = file,
                    title = title,
                    watermark = watermark,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "pdf_path"
        private const val EXTRA_TITLE = "pdf_title"
        private const val EXTRA_WATERMARK = "pdf_watermark"

        fun intent(
            context: Context,
            pdfPath: String,
            title: String,
            watermark: String? = null,
        ): Intent =
            Intent(context, PdfViewerActivity::class.java)
                .putExtra(EXTRA_PATH, pdfPath)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_WATERMARK, watermark)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(
    file: File,
    title: String,
    watermark: String?,
    onBack: () -> Unit,
) {
    val session = remember(file, watermark) { PdfSession(file, watermark) }
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        val widthPx = with(LocalDensity.current) {
            LocalConfiguration.current.screenWidthDp.dp.roundToPx()
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            items(session.pageCount) { index ->
                PdfPage(session = session, index = index, targetWidthPx = widthPx)
            }
        }
    }
}

@Composable
private fun PdfPage(session: PdfSession, index: Int, targetWidthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, session, index, targetWidthPx) {
        value = session.renderPage(index, targetWidthPx)
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(Color.White),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(session.pageAspectRatio(index))
                .padding(bottom = 8.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Wraps PdfRenderer, which allows only one open page at a time — all
 * rendering is serialized behind a mutex and moved off the main thread.
 */
private class PdfSession(file: File, private val watermark: String?) {

    private val descriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()

    val pageCount: Int = renderer.pageCount

    fun pageAspectRatio(index: Int): Float = ratios.getOrElse(index) { DEFAULT_RATIO }

    private val ratios: List<Float> = List(pageCount) { i ->
        renderer.openPage(i).use { page ->
            if (page.height > 0) page.width.toFloat() / page.height else DEFAULT_RATIO
        }
    }

    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val width = targetWidthPx.coerceAtLeast(1)
                        val height = (width * page.height / page.width).coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        if (!watermark.isNullOrBlank()) {
                            com.eugeneboon.docscanner.util.PdfEditor
                                .applyWatermark(bitmap, watermark)
                        }
                        bitmap
                    }
                }.getOrNull()
            }
        }

    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    private companion object {
        const val DEFAULT_RATIO = 0.707f // A4 portrait
    }
}
