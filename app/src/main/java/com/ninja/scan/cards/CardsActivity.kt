package com.ninja.scan.cards

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ninja.scan.DocScannerApp
import com.ninja.scan.R
import com.ninja.scan.data.BusinessCard
import com.ninja.scan.ui.theme.DocScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File

/** Business card library: scan cards, manage contacts, export CSV/Excel. */
class CardsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoStartScan =
            savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_SCAN, false)
        setContent {
            DocScannerTheme {
                CardsScreen(autoStartScan = autoStartScan, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_START_SCAN = "start_scan"

        fun intent(context: Context, startScan: Boolean = false): Intent =
            Intent(context, CardsActivity::class.java)
                .putExtra(EXTRA_START_SCAN, startScan)
    }
}

private val cardScannerOptions = GmsDocumentScannerOptions.Builder()
    .setGalleryImportAllowed(true)
    .setPageLimit(1)
    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
    .build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardsScreen(autoStartScan: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DocScannerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val cards by app.repository.cards.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf<BusinessCard?>(null) }
    var deleting by remember { mutableStateOf<BusinessCard?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val imageUri = GmsDocumentScanningResult
            .fromActivityResultIntent(activityResult.data)
            ?.pages.orEmpty()
            .firstOrNull()?.imageUri
        if (imageUri != null) {
            parsing = true
            scope.launch {
                draft = runCatching { app.repository.parseCardImage(imageUri) }.getOrNull()
                parsing = false
                if (draft == null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.card_scan_failed)
                    )
                }
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { destination ->
        if (destination != null) scope.launch {
            val ok = app.repository.exportCardsCsv(cards, destination)
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.exported_contacts else R.string.export_failed)
            )
        }
    }

    val xlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { destination ->
        if (destination != null) scope.launch {
            val ok = app.repository.exportCardsXlsx(cards, destination)
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.exported_contacts else R.string.export_failed)
            )
        }
    }

    val launchCardScanner: () -> Unit = {
        GmsDocumentScanning.getClient(cardScannerOptions)
            .getStartScanIntent(context as ComponentActivity)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
    }

    // Launched from the main screen's "Scan business card" button: go
    // straight into capture instead of landing on the list first.
    LaunchedEffect(Unit) {
        if (autoStartScan) launchCardScanner()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.business_cards)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { exportMenuOpen = true },
                            enabled = cards.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_csv)) },
                                onClick = {
                                    exportMenuOpen = false
                                    csvLauncher.launch("ninja-scan-contacts.csv")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_excel)) },
                                onClick = {
                                    exportMenuOpen = false
                                    xlsxLauncher.launch("ninja-scan-contacts.xlsx")
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = launchCardScanner,
                icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                text = { Text(stringResource(R.string.scan_card)) },
            )
        },
    ) { padding ->
        when {
            parsing -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            cards.isEmpty() -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        Icons.Filled.ContactPage,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.no_cards_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.no_cards_body),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    CardRow(
                        card = card,
                        onSaveToContacts = { saveToContacts(context, card) },
                        onEdit = { draft = card },
                        onDelete = { deleting = card },
                    )
                }
            }
        }
    }

    draft?.let { editing ->
        CardEditDialog(
            card = editing,
            onDismiss = { draft = null },
            onSave = { updated ->
                draft = null
                scope.launch { app.repository.saveCard(updated) }
            },
        )
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_card_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_card_body,
                        card.name.ifBlank { card.company },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { app.repository.deleteCard(card) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CardRow(
    card: BusinessCard,
    onSaveToContacts: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbnail = card.thumbnailPath?.let(::File)?.takeIf { it.exists() }
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
                    Icons.Filled.ContactPage,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    card.name.ifBlank { card.company }
                        .ifBlank { stringResource(R.string.business_cards) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                val subtitle = listOf(card.company, card.phone, card.email)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.save_to_contacts)) },
                        onClick = { menuOpen = false; onSaveToContacts() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun CardEditDialog(
    card: BusinessCard,
    onDismiss: () -> Unit,
    onSave: (BusinessCard) -> Unit,
) {
    var name by remember(card) { mutableStateOf(card.name) }
    var company by remember(card) { mutableStateOf(card.company) }
    var jobTitle by remember(card) { mutableStateOf(card.jobTitle) }
    var phone by remember(card) { mutableStateOf(card.phone) }
    var email by remember(card) { mutableStateOf(card.email) }
    var website by remember(card) { mutableStateOf(card.website) }
    var address by remember(card) { mutableStateOf(card.address) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.business_cards)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                @Composable
                fun field(value: String, label: Int, onChange: (String) -> Unit) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(stringResource(label)) },
                        singleLine = true,
                    )
                }
                field(name, R.string.field_name) { name = it }
                field(company, R.string.field_company) { company = it }
                field(jobTitle, R.string.field_job_title) { jobTitle = it }
                field(phone, R.string.field_phone) { phone = it }
                field(email, R.string.field_email) { email = it }
                field(website, R.string.field_website) { website = it }
                field(address, R.string.field_address) { address = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    card.copy(
                        name = name.trim(),
                        company = company.trim(),
                        jobTitle = jobTitle.trim(),
                        phone = phone.trim(),
                        email = email.trim(),
                        website = website.trim(),
                        address = address.trim(),
                    )
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Opens the system "create contact" screen pre-filled from the card. */
private fun saveToContacts(context: Context, card: BusinessCard) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, card.name)
        putExtra(ContactsContract.Intents.Insert.COMPANY, card.company)
        putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.jobTitle)
        putExtra(ContactsContract.Intents.Insert.PHONE, card.phone)
        putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
        putExtra(ContactsContract.Intents.Insert.POSTAL, card.address)
        if (card.website.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.NOTES, card.website)
        }
    }
    runCatching { context.startActivity(intent) }
}
