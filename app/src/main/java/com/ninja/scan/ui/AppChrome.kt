package com.ninja.scan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
        }
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
