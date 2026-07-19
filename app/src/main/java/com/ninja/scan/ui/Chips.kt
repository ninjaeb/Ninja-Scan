package com.ninja.scan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninja.scan.R

/**
 * A chip with a single gesture surface for both tap and long-press.
 *
 * Material3's `FilterChip` installs its own internal clickable node deeper
 * in the modifier chain than any caller-supplied modifier; since Compose's
 * Main pointer-event pass runs child-before-parent, that internal click
 * handling wins the race before an outer long-press detector reliably
 * fires. `combinedClickable` avoids the race entirely by being the only
 * gesture detector on the node, so this chip is built from a plain
 * `Surface` instead of wrapping `FilterChip`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    leadingDot: Color? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            leadingDot?.let {
                Box(Modifier.size(10.dp).clip(CircleShape).background(it))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Parses a "#RRGGBB" hex string into a [Color], falling back to gray. */
fun hexToColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** Converts a [Color] to a "#RRGGBB" hex string, the form tag colors are stored in. */
fun colorToHex(color: Color): String =
    "#%06X".format(0xFFFFFF and color.toArgb())

/**
 * Create-or-edit dialog for a tag (title field + a color-swatch palette).
 * Generalized over plain primitives rather than a specific tag entity type
 * so both Documents' and Cards' tag catalogs can share this UI despite being
 * backed by separate tables.
 */
@Composable
fun TagEditorDialog(
    existingTitle: String = "",
    existingColor: String? = null,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, color: String) -> Unit,
) {
    var title by remember { mutableStateOf(existingTitle) }
    val focusRequester = remember { FocusRequester() }
    val palette = listOf(
        colorResource(R.color.tag_red), colorResource(R.color.tag_orange),
        colorResource(R.color.tag_amber), colorResource(R.color.tag_green),
        colorResource(R.color.tag_mint), colorResource(R.color.tag_blue),
        colorResource(R.color.tag_light_blue), colorResource(R.color.tag_purple),
        colorResource(R.color.tag_lavender), colorResource(R.color.tag_pink),
        Color.Black,
    )
    var selectedIndex by remember {
        mutableStateOf(
            existingColor
                ?.let { hex -> palette.indexOfFirst { colorToHex(it).equals(hex, ignoreCase = true) } }
                ?.takeIf { it >= 0 }
                ?: (palette.size - 1)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.create_tag else R.string.edit_tag)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.tag_title)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Column(Modifier.padding(top = 12.dp)) {
                    palette.chunked(6).forEach { rowColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            rowColors.forEach { swatch ->
                                val index = palette.indexOf(swatch)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                        .clickable { selectedIndex = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (index == selectedIndex) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), colorToHex(palette[selectedIndex])) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(if (isNew) R.string.create else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
