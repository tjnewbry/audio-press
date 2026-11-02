package io.github.tjnewbry.audiopress.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val OUTLINE_WIDTH = 2.dp
private val OUTLINE_CORNER = 8.dp

/** A rounded outline that is dotted ([dotted]) or solid, drawn inside the bounds. */
fun Modifier.outline(
    dotted: Boolean,
    color: Color = OutlineGrey,
): Modifier =
    drawBehind {
        val width = OUTLINE_WIDTH.toPx()
        val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(width * 2, width * 2)) else null
        drawRoundRect(
            color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(OUTLINE_CORNER.toPx()),
            style = Stroke(width, pathEffect = effect),
        )
    }
