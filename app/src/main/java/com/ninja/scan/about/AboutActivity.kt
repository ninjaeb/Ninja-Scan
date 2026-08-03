package com.ninja.scan.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ninja.scan.MainActivity
import com.ninja.scan.R
import com.ninja.scan.cards.CardsActivity
import com.ninja.scan.security.AppLock
import com.ninja.scan.ui.ActionGreen
import com.ninja.scan.ui.SheetAction
import com.ninja.scan.ui.ShareBlue
import com.ninja.scan.ui.ThemeToggleButton
import com.ninja.scan.ui.WhatsAppGreen
import com.ninja.scan.ui.brandedNavigationItemColors
import com.ninja.scan.ui.theme.DocScannerTheme
import com.ninja.scan.ui.theme.LocalePrefs
import com.ninja.scan.ui.theme.ThemePrefs
import kotlin.math.abs

private const val WEBSITE_URL = "https://share2.io/ninja-scan"
private const val COMMUNITY_URL = "https://chat.whatsapp.com/IcN8xKtmPmF2Icz9wNstYt?s=cl&p=a&mlu=4"

/** Short app intro, ways to share Ninja Scan or join the community, and a changelog. */
class AboutActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocalePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkTheme by remember { mutableStateOf(ThemePrefs.isDark(this)) }
            var appLockAvailable by remember { mutableStateOf(AppLock.isAvailable(this)) }
            var appLockEnabled by remember { mutableStateOf(AppLock.isEnabled(this)) }
            var currentLanguage by remember { mutableStateOf(LocalePrefs.getLanguage(this)) }
            // Documents/Cards/About are separate Activities; re-read the
            // shared preference on every resume so a toggle made on another
            // screen is reflected here after navigating back — also picks up
            // enrollment changes made in the device's system settings.
            LifecycleResumeEffect(Unit) {
                isDarkTheme = ThemePrefs.isDark(this@AboutActivity)
                appLockAvailable = AppLock.isAvailable(this@AboutActivity)
                appLockEnabled = AppLock.isEnabled(this@AboutActivity)
                currentLanguage = LocalePrefs.getLanguage(this@AboutActivity)
                onPauseOrDispose { }
            }
            DocScannerTheme(darkTheme = isDarkTheme) {
                AboutScreen(
                    onBack = {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(R.anim.slide_in_from_right, R.anim.slide_out_to_left)
                    },
                    // Unlike onBack, this always lands on Documents itself
                    // regardless of whether About was opened from Documents
                    // or from Cards — finish() alone would only return to
                    // whichever one it actually came from.
                    onOpenDocuments = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        )
                    },
                    onOpenCards = { startActivity(CardsActivity.intent(this)) },
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = {
                        isDarkTheme = !isDarkTheme
                        ThemePrefs.setDark(this, isDarkTheme)
                    },
                    appLockAvailable = appLockAvailable,
                    appLockEnabled = appLockEnabled,
                    onToggleAppLock = {
                        appLockEnabled = it
                        AppLock.setEnabled(this, it)
                    },
                    currentLanguage = currentLanguage,
                    onSelectLanguage = { language ->
                        LocalePrefs.setLanguage(this, language)
                        // A per-Activity Configuration override only takes
                        // effect from attachBaseContext at creation time, and
                        // this app has several independent entry Activities
                        // (Main/Cards/About/Viewer/PageEditor) — recreating
                        // just this one would leave the rest on the old
                        // language until they happened to restart on their
                        // own. A full restart back to Documents is the
                        // simplest way to put every screen on the new
                        // language at once.
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        )
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenCards: () -> Unit,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    appLockAvailable: Boolean,
    appLockEnabled: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    currentLanguage: String,
    onSelectLanguage: (String) -> Unit,
) {
    val context = LocalContext.current
    var showLanguageDialog by remember { mutableStateOf(false) }

    Scaffold(
        // A horizontal swipe goes back to Documents/Cards, matching the same
        // gesture already wired between those two screens.
        modifier = Modifier.pointerInput(onBack) {
            val threshold = 120.dp.toPx()
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = { if (abs(totalDrag) > threshold) onBack() },
                onDragCancel = { totalDrag = 0f },
            ) { change, dragAmount ->
                totalDrag += dragAmount
                change.consume()
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    ThemeToggleButton(isDarkTheme = isDarkTheme, onToggle = onToggleTheme)
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenDocuments,
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_documents)) },
                    colors = brandedNavigationItemColors(),
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenCards,
                    icon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_cards)) },
                    colors = brandedNavigationItemColors(),
                )
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_about)) },
                    colors = brandedNavigationItemColors(),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(108.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.about_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (appLockAvailable) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_lock_toggle_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.app_lock_toggle_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = appLockEnabled, onCheckedChange = onToggleAppLock)
                }
            }
            HorizontalDivider()
            SheetAction(
                Icons.Filled.Translate,
                stringResource(R.string.language_setting),
                iconTint = ActionGreen,
                onClick = { showLanguageDialog = true },
            )
            HorizontalDivider()
            SheetAction(
                Icons.Filled.Share,
                stringResource(R.string.share_app),
                iconTint = ShareBlue,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_message))
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_app)))
                },
            )
            SheetAction(
                Icons.Filled.Chat,
                stringResource(R.string.join_community),
                iconTint = WhatsAppGreen,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_URL)))
                },
            )
            SheetAction(
                Icons.Filled.Language,
                stringResource(R.string.visit_website),
                iconTint = ActionGreen,
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)))
                },
            )
            HorizontalDivider()
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                stringArrayResource(R.array.changelog_items).forEach { line ->
                    Text(
                        "•  $line",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            current = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                showLanguageDialog = false
                onSelectLanguage(it)
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        LocalePrefs.SYSTEM_DEFAULT to stringResource(R.string.language_system_default),
        LocalePrefs.ENGLISH to stringResource(R.string.language_option_en),
        LocalePrefs.CHINESE to stringResource(R.string.language_option_zh),
        LocalePrefs.MALAY to stringResource(R.string.language_option_ms),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_dialog_title)) },
        text = {
            Column {
                options.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = code == current, onClick = { onSelect(code) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}
