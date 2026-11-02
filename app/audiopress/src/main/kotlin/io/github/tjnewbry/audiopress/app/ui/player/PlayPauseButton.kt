package io.github.tjnewbry.audiopress.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.app.ui.DISABLED_ALPHA
import io.github.tjnewbry.audiopress.app.ui.MinTouchTarget

private val ICON_SIZE = 28.dp
private val BAR_CORNER = 2.dp

/** A play triangle, or two pause bars, drawn so no icon library is needed. Icon only; the words are for screen readers. */
@Composable
fun PlayPauseButton(
    playing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    Box(
        modifier
            .size(MinTouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ICON_SIZE)) {
            if (playing) {
                val bar = size.width * 0.28f
                drawRoundRect(color, Offset(size.width * 0.12f, 0f), Size(bar, size.height), CornerRadius(BAR_CORNER.toPx()))
                drawRoundRect(color, Offset(size.width * 0.60f, 0f), Size(bar, size.height), CornerRadius(BAR_CORNER.toPx()))
            } else {
                val triangle =
                    Path().apply {
                        moveTo(size.width * 0.18f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.18f, size.height)
                        close()
                    }
                drawPath(triangle, color)
            }
        }
    }
}
