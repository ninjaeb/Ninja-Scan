package com.ninja.scan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninja.scan.R

/** The app icon + app name title, shared by every top-level screen's top bar. */
@Composable
fun AppTitleWithIcon(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The launcher foreground vector carries adaptive-icon safe-zone
        // padding; overdrawing a clipped circle reproduces the launcher
        // look at full glyph size.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(54.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(title)
    }
}

/**
 * The Drive backup cloud icon + its dropdown, shared by every top-level
 * screen's top bar so backup can be toggled, restored, or triggered
 * on-demand from either the Documents or Cards screen.
 */
@Composable
fun DriveMenuButton(
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onBackupNow: () -> Unit,
    hasRecoveryKey: Boolean = false,
    onViewRecoveryKey: () -> Unit = {},
    onSetupRecoveryKey: () -> Unit = {},
) {
    Box {
        IconButton(onClick = { onExpandedChange(true) }) {
            Icon(
                if (enabled) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                contentDescription = stringResource(R.string.drive_backup),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (enabled) R.string.drive_menu_disable
                            else R.string.drive_menu_enable
                        )
                    )
                },
                onClick = { onExpandedChange(false); onToggle() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.restore_from_drive)) },
                onClick = { onExpandedChange(false); onRestore() },
            )
            if (enabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drive_backup_now)) },
                    onClick = { onExpandedChange(false); onBackupNow() },
                )
            }
            // Backup was already on (e.g. from before encryption existed)
            // but this device never generated/entered a recovery key, so
            // nothing actually uploads — a one-tap way to finish that
            // setup instead of the confusing "turn off, then on again".
            if (enabled && !hasRecoveryKey) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.finish_backup_setup)) },
                    onClick = { onExpandedChange(false); onSetupRecoveryKey() },
                )
            }
            if (hasRecoveryKey) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.view_recovery_key)) },
                    onClick = { onExpandedChange(false); onViewRecoveryKey() },
                )
            }
        }
    }
}

/**
 * Sun/moon icon button that switches between light and dark theme, shared
 * by every top-level screen's top bar. Shows the icon for the mode a tap
 * would switch *to*, matching common light/dark toggle conventions.
 */
@Composable
fun ThemeToggleButton(isDarkTheme: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = stringResource(
                if (isDarkTheme) R.string.switch_to_light_theme else R.string.switch_to_dark_theme
            ),
            tint = if (isDarkTheme) EditAmber else ShareBlue,
        )
    }
}

/**
 * The Documents/Cards bottom nav's selected-item colors, shared so the
 * selected tab reads as the app's brand color rather than the generic
 * Material secondary-container tint.
 */
@Composable
fun brandedNavigationItemColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary,
)

/** Item counts read from a running backup/restore WorkInfo's progress Data. */
data class SyncProgress(val current: Int, val total: Int)

/**
 * A slim label + progress bar shown under the top bar while a Drive backup
 * or restore pass is running, so "Backing up..."/"Restoring..." isn't just
 * a one-shot snackbar with no sense of how long it'll take.
 */
@Composable
fun DriveSyncProgressBar(label: String, progress: SyncProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            if (progress.total > 0) "$label (${progress.current}/${progress.total})" else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A small dismissible one-line hint teaching the long-press-to-select
 * gesture, shown until the user closes it (tracked per-screen via
 * [HintPrefs] so it doesn't reappear once acknowledged).
 */
@Composable
fun LongPressHint(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.TouchApp,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.dismiss_hint),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
