package io.github.tjnewbry.audiopress.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The app's theme: Material 3's own light or dark colours, following the phone. */
@Composable
fun AudioPressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

/** The steps of space used across the screens, so every gap and padding comes from one scale. */
object Spacing {
    val Hairline = 2.dp
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
    val Huge = 32.dp
}

/** The space between the screen's edges and its content, left and right. */
val ScreenPadding = Spacing.Large

/** The least height and width of anything that can be tapped (Material's touch target). */
val MinTouchTarget = 48.dp

/** The padding inside an outlined box (the book text in the player, the voice list). */
val OutlinedBoxPadding = 6.dp

/** The grey of the outline drawn round a box. */
val OutlineGrey = Color(0xFF455A64)

/** How strongly a disabled control's colour shows (Material's disabled content alpha). */
const val DISABLED_ALPHA = 0.38f
