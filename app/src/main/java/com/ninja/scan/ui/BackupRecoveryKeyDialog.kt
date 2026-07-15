package com.ninja.scan.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ninja.scan.R
import kotlinx.coroutines.launch

/** Which of the two backup-recovery-key flows [BackupRecoveryKeyDialog] is showing. */
enum class BackupRecoveryMode { GENERATE, ENTER }

/**
 * Shown the first time backup/restore needs an encryption key this device
 * doesn't already have cached: GENERATE creates a new random recovery key
 * and displays it once to save; ENTER unlocks with a code generated
 * elsewhere. There's no password to remember either way — only a code to
 * save, since losing it (same as forgetting a password would be) means the
 * backup can never be decrypted again.
 */
@Composable
fun BackupRecoveryKeyDialog(
    mode: BackupRecoveryMode,
    onDismiss: () -> Unit,
    onGenerate: () -> String,
    onEnter: suspend (code: String) -> Boolean,
    onSuccess: () -> Unit,
) {
    when (mode) {
        BackupRecoveryMode.GENERATE -> GenerateRecoveryKeyDialog(onDismiss, onGenerate, onSuccess)
        BackupRecoveryMode.ENTER -> EnterRecoveryKeyDialog(onDismiss, onEnter, onSuccess)
    }
}

/**
 * Re-shows an already-generated code — e.g. from a "View recovery key" menu
 * item — so seeing it once at setup time isn't the only chance to save it,
 * as long as this same device/install still has the key cached.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewRecoveryKeyDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recovery_key_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.recovery_key_view_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecoveryCodeRow(code)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

/** The code in a read-only text box, with one-tap Copy and "email to myself" actions. */
@Composable
private fun RecoveryCodeRow(code: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = code.chunked(4).joinToString("-"),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.recovery_key_field)) },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy))
        }
        IconButton(
            onClick = {
                val email = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.recovery_key_title))
                    putExtra(Intent.EXTRA_TEXT, code)
                }
                runCatching { context.startActivity(email) }
            },
        ) {
            Icon(Icons.Filled.Email, contentDescription = stringResource(R.string.email_recovery_key))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateRecoveryKeyDialog(
    onDismiss: () -> Unit,
    onGenerate: () -> String,
    onSuccess: () -> Unit,
) {
    // Generated once per dialog instance, not on every recomposition — this
    // is the only time the code is shown at setup, so the dialog itself is
    // the single source of truth for "has the key been created yet." (It
    // can still be viewed again later via "View recovery key" in the Drive
    // menu, as long as this device/install keeps the key cached.)
    val code = remember { onGenerate() }
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recovery_key_generated_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.recovery_key_generated_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecoveryCodeRow(code)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(stringResource(R.string.recovery_key_saved_confirm))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = acknowledged, onClick = onSuccess) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnterRecoveryKeyDialog(
    onDismiss: () -> Unit,
    onEnter: suspend (code: String) -> Boolean,
    onSuccess: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val wrongCode = stringResource(R.string.incorrect_recovery_key)

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.enter_recovery_key_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.enter_recovery_key_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text(stringResource(R.string.recovery_key_field)) },
                        singleLine = false,
                        enabled = !submitting,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        enabled = !submitting,
                        onClick = { clipboard.getText()?.text?.let { code = it; error = null } },
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.paste))
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && code.isNotBlank(),
                onClick = {
                    submitting = true
                    scope.launch {
                        val ok = runCatching { onEnter(code) }.getOrDefault(false)
                        submitting = false
                        if (ok) onSuccess() else error = wrongCode
                    }
                },
            ) {
                Text(stringResource(R.string.unlock))
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
