package com.ninja.scan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.ninja.scan.R
import kotlinx.coroutines.launch

/** Which of the two backup-password flows [BackupPasswordDialog] is showing. */
enum class BackupPasswordMode { SETUP, UNLOCK }

private const val MIN_PASSWORD_LENGTH = 8

/**
 * Prompts for the password that encrypts (SETUP) or decrypts (UNLOCK) the
 * Google Drive backup — shown the first time backup/restore needs a key this
 * device doesn't already have cached. [onSubmit] does the real work
 * (deriving/storing a new key, or checking one against the password already
 * set up elsewhere) and returns whether it succeeded; a false result keeps
 * the dialog open with an inline error instead of dismissing, since for
 * UNLOCK that just means a wrong password worth retrying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupPasswordDialog(
    mode: BackupPasswordMode,
    onDismiss: () -> Unit,
    onSubmit: suspend (password: CharArray) -> Boolean,
    onSuccess: () -> Unit,
) {
    val isSetup = mode == BackupPasswordMode.SETUP
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tooShort = stringResource(R.string.backup_password_too_short)
    val mismatch = stringResource(R.string.backup_passwords_dont_match)
    val setupFailed = stringResource(R.string.backup_password_setup_failed)
    val wrongPassword = stringResource(R.string.incorrect_backup_password)

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (isSetup) R.string.set_backup_password_title else R.string.enter_backup_password_title
                )
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        if (isSetup) R.string.set_backup_password_hint else R.string.enter_backup_password_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(stringResource(R.string.backup_password_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !submitting,
                )
                if (isSetup) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text(stringResource(R.string.confirm_backup_password_field)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !submitting,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && password.isNotEmpty(),
                onClick = {
                    if (isSetup) {
                        if (password.length < MIN_PASSWORD_LENGTH) {
                            error = tooShort
                            return@TextButton
                        }
                        if (password != confirm) {
                            error = mismatch
                            return@TextButton
                        }
                    }
                    submitting = true
                    scope.launch {
                        val ok = runCatching { onSubmit(password.toCharArray()) }.getOrDefault(false)
                        submitting = false
                        if (ok) {
                            onSuccess()
                        } else {
                            error = if (isSetup) setupFailed else wrongPassword
                        }
                    }
                },
            ) {
                Text(stringResource(if (isSetup) R.string.save else R.string.unlock))
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
