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

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposablesSearchField(state: TextFieldState, label: String, tag: String = "note-reference-filter") {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val type = MaterialTheme.typography.bodyMedium
    BasicTextField(
        state = state,
        modifier = Modifier.fillMaxWidth().testTag(tag)
            .semantics { contentDescription = label },
        interactionSource = interaction,
        textStyle = type.copy(color = scheme.onSurface),
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(scheme.primary),
        decorator = { input ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(scheme.surfaceContainer, shape)
                    .border(if (focused) 2.dp else 1.dp, if (focused) scheme.primary else scheme.outlineVariant, shape)
                    .padding(start = 12.dp, end = if (state.text.isEmpty()) 12.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val stroke = 1.5.dp.toPx()
                    drawCircle(scheme.onSurfaceVariant, 4.8.dp.toPx(), Offset(size.width * .42f, size.height * .42f),
                        style = Stroke(stroke))
                    drawLine(scheme.onSurfaceVariant, Offset(size.width * .62f, size.height * .62f),
                        Offset(size.width * .88f, size.height * .88f), stroke, StrokeCap.Round)
                }
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty()) Text(label, color = scheme.onSurfaceVariant, style = type)
                    input()
                }
                if (state.text.isNotEmpty()) {
                    IconButton(
                        onClick = { state.edit { replace(0, length, "") } },
                        modifier = Modifier.semantics { contentDescription = "清除筛选" },
                    ) { Text("×", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge) }
                }
            }
        },
    )
}
