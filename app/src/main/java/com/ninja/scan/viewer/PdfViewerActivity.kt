package com.ninja.scan.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninja.scan.DocScannerApp
import com.ninja.scan.R
import com.ninja.scan.data.ScanDocument
import com.ninja.scan.editor.PageEditorActivity
import com.ninja.scan.ui.ActionGreen
import com.ninja.scan.ui.EditAmber
import com.ninja.scan.ui.FolderIndigo
import com.ninja.scan.ui.HintPrefs
import com.ninja.scan.ui.LongPressHint
import com.ninja.scan.ui.PagesShareSheet
import com.ninja.scan.ui.SaveFormatSheet
import com.ninja.scan.ui.ShareBlue
import com.ninja.scan.ui.ShareFormatSheet
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.ThemePrefs
import com.ninja.scan.util.EditPage
import com.ninja.scan.util.PdfEditor
import com.ninja.scan.util.PrintActions
import com.ninja.scan.util.ShareActions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Displays a scanned PDF in-app with a bottom action bar: Add watermark /
 * Add Scan / Share (format sheet) / Edit pages / Print / Save (PDF or images).
 */
class PdfViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scanId = intent.getLongExtra(EXTRA_SCAN_ID, -1L)
        if (scanId <= 0) {
            finish()
            return
        }
        setContent {
            DocScannerTheme(darkTheme = ThemePrefs.isDark(this)) {
                PdfViewerScreen(scanId = scanId, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_SCAN_ID = "scan_id"

        fun intent(context: Context, scanId: Long): Intent =
            Intent(context, PdfViewerActivity::class.java).putExtra(EXTRA_SCAN_ID, scanId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(scanId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val app = context.applicationContext as DocScannerApp
    val scope = rememberCoroutineScope()

    var scan by remember { mutableStateOf<ScanDocument?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var editingWatermark by remember { mutableStateOf(false) }
    var renamingTitle by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Long-press a page thumbnail to pick several pages and export/share
    // just those, instead of the whole document.
    val selectedPages = remember { mutableStateListOf<Int>() }
    val pageSelectionActive = selectedPages.isNotEmpty()
    var sharingPages by remember { mutableStateOf(false) }
    BackHandler(enabled = pageSelectionActive) { selectedPages.clear() }
    var showLongPressHint by remember {
        mutableStateOf(!HintPrefs.isDismissed(context, HINT_KEY_PAGES))
    }

    LaunchedEffect(scanId) {
        val loaded = app.repository.getScan(scanId)
        if (loaded == null || !File(loaded.pdfPath).exists()) onBack() else scan = loaded
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val current = scan
        if (destination != null && current != null) {
            scope.launch { app.repository.exportTo(current, destination) }
        }
    }

    val addScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val newUris = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            ?.pages.orEmpty().map { it.imageUri }
        val current = scan
        if (newUris.isNotEmpty() && current != null) {
            scope.launch {
                val existingCount = withContext(Dispatchers.IO) {
                    PdfEditor.pageCount(File(current.pdfPath))
                }
                val allPages = List(existingCount) { EditPage.FromPdf(it) } +
                    newUris.map { EditPage.FromImage(it) }
                scan = app.repository.applyPageEdits(current.id, allPages, current.watermark)
            }
        }
    }

    val current = scan
    Scaffold(
        topBar = {
            if (pageSelectionActive) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedPages.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedPages.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        // Composites the whole document's two pages (a
                        // card's front and back, scanned as separate pages)
                        // onto one ID-card-formatted page, replacing this
                        // document in place — restricted to a 2-page
                        // document with both pages selected, since replacing
                        // the file would otherwise drop any other pages.
                        if (selectedPages.size == 2 && current?.pageCount == 2) {
                            IconButton(
                                onClick = {
                                    current?.let { doc ->
                                        val pages = selectedPages.sorted()
                                        selectedPages.clear()
                                        scope.launch {
                                            val result = runCatching {
                                                app.repository.convertPagesToIdCard(
                                                    doc, pages[0], doc, pages[1],
                                                )
                                            }
                                            result.onSuccess { saved ->
                                                // The PDF was rebuilt in place under the
                                                // same document — refresh the viewer's
                                                // state so it reflects the new single
                                                // ID-card page instead of the old pages.
                                                scan = saved
                                                snackbarHostState.showSnackbar(
                                                    context.getString(
                                                        R.string.scan_saved,
                                                        Formatter.formatShortFileSize(context, saved.sizeBytes),
                                                    )
                                                )
                                            }.onFailure {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(
                                                        R.string.scan_failed,
                                                        it.message ?: "unknown error",
                                                    )
                                                )
                                            }
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Badge,
                                    contentDescription = stringResource(R.string.convert_to_id_card),
                                    tint = ActionGreen,
                                )
                            }
                        }
                        IconButton(onClick = { sharingPages = true }) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.share),
                                tint = ShareBlue,
                            )
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        // Tap the title to rename the document.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(enabled = current != null) {
                                renamingTitle = true
                            },
                        ) {
                            Text(
                                current?.title.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (current != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.rename),
                                    modifier = Modifier.size(16.dp),
                                    tint = EditAmber,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = { editingWatermark = true },
                    icon = {
                        Icon(
                            Icons.Filled.BrandingWatermark,
                            contentDescription = null,
                            tint = if (current != null) EditAmber else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.watermark)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = {
                        GmsDocumentScanning.getClient(addScanOptions)
                            .getStartScanIntent(activity)
                            .addOnSuccessListener { sender ->
                                addScanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                    },
                    icon = {
                        Icon(
                            Icons.Filled.AddAPhoto,
                            contentDescription = null,
                            tint = if (current != null) ActionGreen else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.add_scan)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = { sharing = true },
                    icon = {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = null,
                            tint = if (current != null) ShareBlue else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.share)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = {
                        current?.let {
                            context.startActivity(PageEditorActivity.intent(context, it.id))
                        }
                    },
                    icon = {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            tint = if (current != null) EditAmber else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.edit)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = { current?.let { PrintActions.printPdf(activity, it) } },
                    icon = {
                        Icon(
                            Icons.Filled.Print,
                            contentDescription = null,
                            tint = if (current != null) FolderIndigo else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.print)) },
                )
                NavigationBarItem(
                    selected = false,
                    enabled = current != null,
                    onClick = { saving = true },
                    icon = {
                        Icon(
                            Icons.Filled.CloudUpload,
                            contentDescription = null,
                            tint = if (current != null) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current,
                        )
                    },
                    label = { BarLabel(stringResource(R.string.save)) },
                )
            }
        },
    ) { padding ->
        if (current == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val session = remember(current.pdfPath, current.watermark, current.pageCount) {
                PdfSession(File(current.pdfPath), current.watermark, current.isIdCard)
            }
            DisposableEffect(session) {
                onDispose { session.close() }
            }
            val widthPx = with(LocalDensity.current) {
                LocalConfiguration.current.screenWidthDp.dp.roundToPx()
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (showLongPressHint && !pageSelectionActive && session.pageCount > 1) {
                    item {
                        LongPressHint(
                            text = stringResource(R.string.hint_long_press_pages),
                            onDismiss = {
                                showLongPressHint = false
                                HintPrefs.dismiss(context, HINT_KEY_PAGES)
                            },
                        )
                    }
                }
                items(session.pageCount) { index ->
                    PdfPage(
                        session = session,
                        index = index,
                        targetWidthPx = widthPx,
                        selectionMode = pageSelectionActive,
                        selected = index in selectedPages,
                        onClick = {
                            if (pageSelectionActive) {
                                if (index in selectedPages) selectedPages.remove(index)
                                else selectedPages.add(index)
                            }
                        },
                        onLongClick = {
                            if (index in selectedPages) selectedPages.remove(index)
                            else selectedPages.add(index)
                        },
                    )
                }
            }
        }
    }

    if (sharing && current != null) {
        ShareFormatSheet(
            scan = current,
            onDismiss = { sharing = false },
            onPdf = { sharing = false; ShareActions.sharePdf(activity, current) },
            onImages = { sharing = false; ShareActions.shareImages(activity, current) },
            onLongImage = { sharing = false; ShareActions.shareLongImage(activity, current) },
            onSeparatePdfs = {
                sharing = false; ShareActions.shareSeparatePdfs(activity, current)
            },
        )
    }

    if (sharingPages && current != null) {
        val pages = selectedPages.sorted()
        PagesShareSheet(
            pageCount = pages.size,
            onDismiss = { sharingPages = false },
            onPdf = {
                sharingPages = false
                selectedPages.clear()
                ShareActions.sharePagesPdf(activity, current, pages)
            },
            onImages = {
                sharingPages = false
                selectedPages.clear()
                ShareActions.sharePageImages(activity, current, pages)
            },
            onLongImage = {
                sharingPages = false
                selectedPages.clear()
                ShareActions.sharePagesLongImage(activity, current, pages)
            },
            onSeparatePdfs = {
                sharingPages = false
                selectedPages.clear()
                ShareActions.sharePagesSeparatePdfs(activity, current, pages)
            },
        )
    }

    if (saving && current != null) {
        SaveFormatSheet(
            scan = current,
            onDismiss = { saving = false },
            onPdf = { saving = false; exportLauncher.launch("${current.title}.pdf") },
            onImages = {
                saving = false
                scope.launch {
                    val count = app.repository.saveImagesToDevice(current)
                    val message = if (count > 0) {
                        context.getString(R.string.saved_images_to_device, count)
                    } else {
                        context.getString(R.string.save_images_failed)
                    }
                    snackbarHostState.showSnackbar(message)
                }
            },
        )
    }

    if (editingWatermark && current != null) {
        var text by remember(current.id) { mutableStateOf(current.watermark.orEmpty()) }
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { editingWatermark = false },
            title = { Text(stringResource(R.string.watermark)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.watermark_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .focusRequester(focusRequester),
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        enabled = current.watermark != null || text.isNotBlank(),
                        onClick = {
                            editingWatermark = false
                            scope.launch { scan = app.repository.updateWatermark(current, null) }
                        },
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                    TextButton(onClick = {
                        editingWatermark = false
                        scope.launch { scan = app.repository.updateWatermark(current, text) }
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingWatermark = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (renamingTitle && current != null) {
        var title by remember(current.id) { mutableStateOf(current.title) }
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { renamingTitle = false },
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
                TextButton(onClick = {
                    renamingTitle = false
                    val trimmed = title.trim()
                    if (trimmed.isNotEmpty()) {
                        scope.launch {
                            app.repository.rename(current, trimmed)
                            scan = current.copy(title = trimmed)
                        }
                    }
                }) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTitle = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Single-line bottom-bar label that never wraps ("Watermark" fits a 6-item bar). */
@Composable
private fun BarLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
    )
}

private const val HINT_KEY_PAGES = "pages"

private val addScanOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(50)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPage(
    session: PdfSession,
    index: Int,
    targetWidthPx: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, session, index, targetWidthPx) {
        value = session.renderPage(index, targetWidthPx)
    }
    val currentBitmap = bitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(session.pageAspectRatio(index))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        if (selectionMode) {
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Wraps PdfRenderer, which allows only one open page at a time — all
 * rendering is serialized behind a mutex and moved off the main thread.
 */
private class PdfSession(file: File, private val watermark: String?, private val isIdCard: Boolean) {

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
                            PdfEditor.applyWatermark(bitmap, watermark, isIdCard)
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
