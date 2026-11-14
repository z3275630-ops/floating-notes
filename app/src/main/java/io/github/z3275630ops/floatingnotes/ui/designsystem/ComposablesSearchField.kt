/*
 * Copyright (c) 2026 Composable Horizons
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

// Adapted from Composables UI TextField.kt, upstream bbaf1ba.
package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposablesSearchField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    tag: String = "note-reference-filter",
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val type = MaterialTheme.typography.bodyMedium
    BasicTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = label },
        interactionSource = interaction,
        textStyle = type.copy(color = scheme.onSurface),
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(scheme.primary),
        decorator = { input ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(scheme.surface.copy(alpha = 0.96f), shape)
                    .border(
                        width = if (focused) 1.8.dp else 1.dp,
                        color = if (focused) scheme.primary else scheme.outline.copy(alpha = 0.34f),
                        shape = shape,
                    )
                    .padding(start = 14.dp, end = if (state.text.isEmpty()) 14.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MintIcons.Search,
                    contentDescription = null,
                    tint = if (focused) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            top = 10.dp,
                            bottom = 10.dp,
                            end = if (state.text.isEmpty()) 2.dp else 8.dp,
                        )
                        .clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = label,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = type,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    input()
                }
                if (state.text.isNotEmpty()) {
                    IconButton(
                        onClick = { state.edit { replace(0, length, "") } },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "清除筛选" },
                    ) {
                        Icon(
                            imageVector = MintIcons.Close,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        },
    )
}
