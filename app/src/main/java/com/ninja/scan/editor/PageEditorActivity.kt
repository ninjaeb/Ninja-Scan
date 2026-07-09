package com.ninja.scan.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninja.scan.DocScannerApp
import com.ninja.scan.R
import com.ninja.scan.ui.ActionGreen
import com.ninja.scan.ui.DestructiveRed
import com.ninja.scan.ui.EditAmber
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.ThemePrefs
import com.ninja.scan.util.EditPage
import com.ninja.scan.util.ImageOptimizer
import com.ninja.scan.util.PdfEditor
import com.ninja.scan.util.rotatedClockwise
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edits an existing scan: reorder, rotate, and remove pages, append freshly
 * scanned pages, and stamp an optional text watermark across the document.
 */
class PageEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scanId = intent.getLongExtra(EXTRA_SCAN_ID, -1L)
        if (scanId <= 0) {
            finish()
            return
        }
        setContent {
            DocScannerTheme(darkTheme = ThemePrefs.isDark(this)) {
                PageEditorScreen(scanId = scanId, onDone = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_SCAN_ID = "scan_id"

        fun intent(context: Context, scanId: Long): Intent =
            Intent(context, PageEditorActivity::class.java).putExtra(EXTRA_SCAN_ID, scanId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageEditorScreen(scanId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScannerApp
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    val pages = remember { mutableListOf<EditPage>().toMutableStateList() }
    var watermark by remember { mutableStateOf("") }
    var editingWatermark by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffectLoad(scanId, app) { scan, count ->
        if (scan == null || count == 0) {
            onDone()
        } else {
            title = scan.title
            pdfFile = File(scan.pdfPath)
            watermark = scan.watermark.orEmpty()
            pages.clear()
            pages.addAll(List(count) { EditPage.FromPdf(it) })
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            ?.pages.orEmpty()
            .forEach { pages.add(EditPage.FromImage(it.imageUri)) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { editingWatermark = true }, enabled = !saving) {
                        Icon(
                            Icons.Filled.BrandingWatermark,
                            contentDescription = stringResource(R.string.watermark),
                            tint = if (!saving) EditAmber else LocalContentColor.current,
                        )
                    }
                    IconButton(
                        onClick = {
                            GmsDocumentScanning.getClient(addPagesOptions)
                                .getStartScanIntent(context as ComponentActivity)
                                .addOnSuccessListener { sender ->
                                    scannerLauncher.launch(
                                        IntentSenderRequest.Builder(sender).build()
                                    )
                                }
                        },
                        enabled = !saving,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.add_pages),
                            tint = if (!saving) ActionGreen else LocalContentColor.current,
                        )
                    }
                    IconButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val result = runCatching {
                                    app.repository.applyPageEdits(
                                        scanId,
                                        pages.toList(),
                                        watermark.takeIf { it.isNotBlank() },
                                    )
                                }
                                result
                                    .onSuccess { onDone() }
                                    .onFailure { e ->
                                        saving = false
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.edit_failed,
                                                e.message ?: "unknown error"
                                            ),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                            }
                        },
                        enabled = !saving && pages.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.save),
                            tint = if (!saving && pages.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val file = pdfFile
        if (file == null || saving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(pages) { index, page ->
                    PageRow(
                        pdf = file,
                        page = page,
                        position = index + 1,
                        total = pages.size,
                        onMoveUp = {
                            if (index > 0) {
                                val item = pages.removeAt(index)
                                pages.add(index - 1, item)
                            }
                        },
                        onMoveDown = {
                            if (index < pages.lastIndex) {
                                val item = pages.removeAt(index)
                                pages.add(index + 1, item)
                            }
                        },
                        onRotate = { pages[index] = page.rotatedClockwise() },
                        onRemove = { if (pages.size > 1) pages.removeAt(index) },
                        canRemove = pages.size > 1,
                    )
                }
            }
        }
    }

    if (editingWatermark) {
        var text by remember { mutableStateOf(watermark) }
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
                TextButton(onClick = { watermark = text; editingWatermark = false }) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingWatermark = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LaunchedEffectLoad(
    scanId: Long,
    app: DocScannerApp,
    onLoaded: (com.ninja.scan.data.ScanDocument?, Int) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(scanId) {
        val scan = app.repository.getScan(scanId)
        val count = scan?.let {
            withContext(Dispatchers.IO) { PdfEditor.pageCount(File(it.pdfPath)) }
        } ?: 0
        onLoaded(scan, count)
    }
}

@Composable
private fun PageRow(
    pdf: File,
    page: EditPage,
    position: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate: () -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbnail by produceState<Bitmap?>(initialValue = null, page) {
                value = withContext(Dispatchers.IO) {
                    when (page) {
                        is EditPage.FromPdf ->
                            PdfEditor.renderPageFromFile(
                                pdf, page.index, THUMBNAIL_DIMENSION, page.rotation
                            )
                        is EditPage.FromImage ->
                            ImageOptimizer.decodeImage(context, page.uri, THUMBNAIL_DIMENSION)
                                ?.let { PdfEditor.rotate(it, page.rotation) }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val current = thumbnail
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    stringResource(R.string.page_position, position, total),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row {
                    IconButton(onClick = onMoveUp, enabled = position > 1) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.move_up),
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = position < total) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.move_down),
                        )
                    }
                    IconButton(onClick = onRotate) {
                        Icon(
                            Icons.Filled.RotateRight,
                            contentDescription = stringResource(R.string.rotate_page),
                        )
                    }
                    IconButton(onClick = onRemove, enabled = canRemove) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete_page),
                            tint = if (canRemove) DestructiveRed else LocalContentColor.current,
                        )
                    }
                }
            }
        }
    }
}

private const val THUMBNAIL_DIMENSION = 400

private val addPagesOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(50)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
    .build()
