package io.github.tjnewbry.audiopress.app.ui.pdfimport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.tjnewbry.audiopress.app.MarkOption
import io.github.tjnewbry.audiopress.app.PageRange
import io.github.tjnewbry.audiopress.app.PdfPreviews
import io.github.tjnewbry.audiopress.app.markOptions
import io.github.tjnewbry.audiopress.app.ui.MinTouchTarget
import io.github.tjnewbry.audiopress.app.ui.Spacing

/** The left margin, where the markers and dashes sit; as wide as a touch target, so a dash is easy to tap. */
internal val MARGIN_WIDTH = MinTouchTarget

/** The width of the scroll thumb's track at the right. */
internal val THUMB_WIDTH = 40.dp

/** The gap above every page, where the boundary marks sit; the list ends with one more gap below the last page. */
internal val PAGE_GAP = 28.dp

private val MAX_PAGE_WIDTH = 300.dp
private val MIN_PAGE_WIDTH = 80.dp
private val MARK_BOX_HEIGHT = 44.dp
private const val DIMMED = 0.3f

/** The widest a preview is drawn, in pixels, so a page costs little memory whatever the screen. */
private const val PREVIEW_MAX_PX = 360

private val MarkRed = Color(0xFFD32F2F)
private val MarkGrey = Color(0xFF8A8A8A)
private val PageBackground = Color.White
private val PageBorder = Color(0xFFBDBDBD)
private val MissingPageText = Color(0xFF757575)
private val PageNumberText = Color(0xFF424242)
private val PageNumberBackground = Color(0xCCFFFFFF)

private val DASH_HALF_LENGTH = 10.dp
private val DASH_THICKNESS = 3.dp
private val ARROW_LENGTH = 26.dp
private val ARROW_HEAD = 6.dp
private val ARROW_STROKE = 2.5.dp
private val POPUP_CORNER = 8.dp

/**
 * The page previews of the PDF, one under the other, each [PAGE_GAP] below the last. In the left margin a red marker shows the
 * top of the first imported page and the bottom of the last; a grey dash at every other boundary opens the "Mark as" popup,
 * which calls [onMark]. Pages outside [range] are dimmed. A scroll thumb runs down the right edge.
 */
@Composable
internal fun PageList(
    previews: PdfPreviews,
    total: Int,
    range: PageRange,
    onMark: (boundary: Int, option: MarkOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    var menuAt by rememberSaveable { mutableStateOf<Int?>(null) } // the boundary whose "Mark as" popup is open
    BoxWithConstraints(modifier) {
        val pageWidth = (maxWidth - MARGIN_WIDTH - THUMB_WIDTH - Spacing.Small).coerceIn(MIN_PAGE_WIDTH, MAX_PAGE_WIDTH)
        val pageHeight = pageWidth * previews.aspect
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }.coerceAtMost(PREVIEW_MAX_PX)

        @Composable
        fun mark(boundary: Int) = BoundaryMark(boundary, range, total, menuAt == boundary, { menuAt = boundary }, { menuAt = null }, onMark)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(total, key = { it }) { index ->
                val page = index + 1
                Box(Modifier.fillMaxWidth().height(PAGE_GAP + pageHeight)) {
                    mark(index)
                    PagePreview(
                        page,
                        previews,
                        widthPx,
                        dimmed = !range.contains(page),
                        modifier = Modifier.padding(start = MARGIN_WIDTH, top = PAGE_GAP).size(pageWidth, pageHeight),
                    )
                }
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().height(PAGE_GAP)) { mark(total) }
            }
        }
        ScrollThumb(state, total, with(LocalDensity.current) { PAGE_GAP.toPx() })
    }
}

/** One page: its preview, drawn when it comes into view, or a blank page with its number when it cannot be drawn. */
@Composable
private fun PagePreview(
    page: Int,
    previews: PdfPreviews,
    widthPx: Int,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, page, widthPx) {
        value = previews.page(page, widthPx)?.asImageBitmap()
    }
    Box(
        modifier
            .alpha(if (dimmed) DIMMED else 1f)
            .background(PageBackground)
            .border(1.dp, PageBorder)
            .semantics { contentDescription = "Page $page${if (dimmed) ", not imported" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
            Text("Page $page", color = MissingPageText, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "$page",
            color = PageNumberText,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(PageNumberBackground)
                    .padding(horizontal = Spacing.ExtraSmall),
        )
    }
}

/**
 * What sits in the left margin at [boundary]: a red dash with a down arrow where the imported pages start, a red dash with an
 * up arrow where they end, and elsewhere a grey dash that opens the "Mark as" popup. The mark is centred in the gap above the
 * page below the boundary.
 */
@Composable
private fun BoundaryMark(
    boundary: Int,
    range: PageRange,
    total: Int,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onMark: (Int, MarkOption) -> Unit,
) {
    val box = Modifier.offset(y = PAGE_GAP / 2 - MARK_BOX_HEIGHT / 2).size(MARGIN_WIDTH, MARK_BOX_HEIGHT)
    when (boundary) {
        range.startBoundary -> {
            Canvas(box.semantics { contentDescription = "First page imported: page ${range.start}" }) { drawMarker(MarkRed, down = true) }
        }

        range.endBoundary -> {
            Canvas(box.semantics { contentDescription = "Last page imported: page ${range.end}" }) { drawMarker(MarkRed, down = false) }
        }

        else -> {
            val options = markOptions(boundary, range, total)
            Box(
                box
                    .clickable(onClickLabel = "Mark as start or end") { onOpen() }
                    .semantics { contentDescription = "Page boundary after page $boundary" }
                    .drawBehind { drawDash(MarkGrey) },
            ) {
                if (menuOpen && options.isNotEmpty()) {
                    MarkAsPopup(options, onClose = onClose, onChoose = { option -> onMark(boundary, option) })
                }
            }
        }
    }
}

/** "Mark as" with a button for each of the [options], just right of the margin; tapping outside closes it. */
@Composable
private fun MarkAsPopup(
    options: List<MarkOption>,
    onClose: () -> Unit,
    onChoose: (MarkOption) -> Unit,
) {
    Popup(
        alignment = Alignment.CenterStart,
        offset = IntOffset(with(LocalDensity.current) { MARGIN_WIDTH.roundToPx() }, 0),
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(shape = RoundedCornerShape(POPUP_CORNER), shadowElevation = 6.dp, tonalElevation = 3.dp) {
            Column(Modifier.padding(horizontal = Spacing.Medium, vertical = Spacing.Small)) {
                Text("Mark as", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    for (option in options) {
                        TextButton(onClick = {
                            onClose()
                            onChoose(option)
                        }) { Text(if (option == MarkOption.START) "start" else "end") }
                    }
                }
            }
        }
    }
}

/** A short rounded dash across the middle of the box. */
private fun DrawScope.drawDash(color: Color) {
    val half = DASH_HALF_LENGTH.toPx()
    val thickness = DASH_THICKNESS.toPx()
    drawRoundRect(
        color,
        Offset(size.width / 2 - half, size.height / 2 - thickness / 2),
        Size(half * 2, thickness),
        CornerRadius(thickness / 2),
    )
}

/** A dash at the middle of the box with an arrow running from it, down towards the page below or up to the page above. */
private fun DrawScope.drawMarker(
    color: Color,
    down: Boolean,
) {
    drawDash(color)
    val x = size.width / 2
    val sign = if (down) 1f else -1f
    val y0 = size.height / 2
    val tip = y0 + sign * ARROW_LENGTH.toPx()
    val head = ARROW_HEAD.toPx()
    drawLine(color, Offset(x, y0), Offset(x, tip), strokeWidth = ARROW_STROKE.toPx())
    val arrow =
        Path().apply {
            moveTo(x - head, tip - sign * head * 1.4f)
            lineTo(x, tip)
            lineTo(x + head, tip - sign * head * 1.4f)
        }
    drawPath(arrow, color, style = Stroke(ARROW_STROKE.toPx()))
}
