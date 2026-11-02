package io.github.tjnewbry.audiopress.app

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MARGIN_WIDTH = 48.dp
private val THUMB_WIDTH = 40.dp
private val THUMB_HEIGHT = 48.dp
private val PAGE_GAP = 28.dp
private val MAX_PAGE_WIDTH = 300.dp
private val MARK_BOX = 44.dp
private val MarkRed = Color(0xFFD32F2F)
private val MarkGrey = Color(0xFF8A8A8A)
private const val DIMMED = 0.3f
private const val PREVIEW_MAX_PX = 360

/**
 * The screen between picking a PDF and importing it: a header (Cancel, the book's name, Import), the PDF's length and the
 * chosen page range (two number boxes), and a scrolling list of page previews with markers for the first and last page, grey
 * dashes at every page boundary to move them, and a scroll thumb. The rules are in `ImportRange.kt`.
 */
@Composable
fun ImportScreen(
    pending: PendingImport,
    onCancel: () -> Unit,
    onImport: (range: IntRange, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = pending.totalPages
    var start by rememberSaveable { mutableIntStateOf(1) }
    var end by rememberSaveable { mutableIntStateOf(total) }
    var startText by rememberSaveable { mutableStateOf("1") }
    var endText by rememberSaveable { mutableStateOf(total.toString()) }
    var typedName by rememberSaveable { mutableStateOf<String?>(null) }
    var interacted by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val range = PageRange(start, end)
    val baseName = remember(pending) { defaultImportName(pending.title, pending.fileName) }
    val name = importName(baseName, typedName, range, total)
    val previews = remember(pending) { PdfPreviews(pending.file) }
    DisposableEffect(previews) { onDispose { previews.close() } }

    fun show(chosen: PageRange) {
        start = chosen.start
        end = chosen.end
        startText = chosen.start.toString()
        endText = chosen.end.toString()
    }

    fun settleBoxes() = show(settle(range, startText.toIntOrNull(), endText.toIntOrNull(), total))

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable {
                            interacted = true
                            renaming = true
                        }.padding(horizontal = 8.dp, vertical = 8.dp)
                        .semantics { contentDescription = "Book name: $name. Tap to rename." },
            )
            TextButton(onClick = {
                if (needsNameConfirmation(interacted, typedName)) confirming = true else onImport(range.toIntRange(), name)
            }) { Text("Import") }
        }
        // Everything below the header counts as using the screen; the Import button above does not.
        Column(Modifier.fillMaxSize().noteTouches { interacted = true }) {
            val length: @Composable () -> Unit = { Text("PDF length: ${if (total == 1) "1 page" else "$total pages"}.") }
            val rangeRow: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Selected range:")
                    NumberBox(
                        startText,
                        "First page",
                        error = startText.toIntOrNull() != start,
                        onChange = {
                            startText = correctTyped(it, total)
                            start = typeStart(range, startText.toIntOrNull()).start
                        },
                        onLeave = { settleBoxes() },
                    )
                    Text("-")
                    NumberBox(
                        endText,
                        "Last page",
                        error = endText.toIntOrNull() != end,
                        onChange = {
                            endText = correctTyped(it, total)
                            end = typeEnd(range, endText.toIntOrNull(), total).end
                        },
                        onLeave = { settleBoxes() },
                    )
                }
            }
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    length()
                    rangeRow()
                }
            } else {
                length()
                rangeRow()
            }
            if (!previews.usable) Text("Page previews are not available for this PDF.", style = MaterialTheme.typography.labelMedium)
            PageList(
                previews = previews,
                total = total,
                range = range,
                onMark = { boundary, option -> show(markAt(boundary, option, range, total)) },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
    if (renaming) {
        RenameDialog(name, onDismiss = { renaming = false }, onRename = {
            val chosen = it.trim()
            if (chosen.isNotEmpty() && chosen != name) typedName = chosen
        })
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Import as \"$name\"?") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        confirming = false
                        onImport(range.toIntRange(), name)
                    }) { Text("Import") }
                    TextButton(onClick = {
                        confirming = false
                        renaming = true
                    }) { Text("Rename") }
                    TextButton(onClick = { confirming = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/** Notes every finger that touches this part of the screen, without taking the touch from anything. */
private fun Modifier.noteTouches(onTouch: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed }) onTouch()
            }
        }
    }

@Composable
private fun NumberBox(
    text: String,
    description: String,
    error: Boolean,
    onChange: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val focus = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    val border = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier =
            Modifier
                .widthIn(min = 72.dp)
                .width(IntrinsicSize.Min)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) onLeave()
                    hadFocus = it.isFocused
                }.semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(Modifier.border(1.dp, border, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) { inner() }
        },
    )
}

@Composable
private fun PageList(
    previews: PdfPreviews,
    total: Int,
    range: PageRange,
    onMark: (boundary: Int, option: MarkOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    var menuAt by rememberSaveable { mutableStateOf<Int?>(null) } // the boundary whose "Mark as" popup is open
    BoxWithConstraints(modifier) {
        val pageWidth = (maxWidth - MARGIN_WIDTH - THUMB_WIDTH - 8.dp).coerceIn(80.dp, MAX_PAGE_WIDTH)
        val pageHeight = pageWidth * previews.aspect
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }.coerceAtMost(PREVIEW_MAX_PX)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(total, key = { it }) { index ->
                val page = index + 1
                Box(Modifier.fillMaxWidth().height(PAGE_GAP + pageHeight)) {
                    BoundaryMark(index, range, total, menuAt == index, { menuAt = index }, { menuAt = null }, onMark)
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
                Box(Modifier.fillMaxWidth().height(PAGE_GAP)) {
                    BoundaryMark(total, range, total, menuAt == total, { menuAt = total }, { menuAt = null }, onMark)
                }
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
            .background(Color.White)
            .border(1.dp, Color(0xFFBDBDBD))
            .semantics { contentDescription = "Page $page${if (dimmed) ", not imported" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
            Text("Page $page", color = Color(0xFF757575), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "$page",
            color = Color(0xFF424242),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
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
    val box = Modifier.offset(y = PAGE_GAP / 2 - MARK_BOX / 2).size(MARGIN_WIDTH, MARK_BOX)
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
                    Popup(
                        alignment = Alignment.CenterStart,
                        offset = IntOffset(with(LocalDensity.current) { MARGIN_WIDTH.roundToPx() }, 0),
                        onDismissRequest = onClose,
                        properties = PopupProperties(focusable = true),
                    ) {
                        Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 6.dp, tonalElevation = 3.dp) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("Mark as", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (option in options) {
                                        TextButton(onClick = {
                                            onClose()
                                            onMark(boundary, option)
                                        }) { Text(if (option == MarkOption.START) "start" else "end") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawDash(color: Color) {
    val half = 10.dp.toPx()
    drawRoundRect(
        color,
        Offset(size.width / 2 - half, size.height / 2 - 1.5.dp.toPx()),
        Size(half * 2, 3.dp.toPx()),
        CornerRadius(1.5.dp.toPx()),
    )
}

/** A red dash at the middle of the box with an arrow running from it, down towards the page below or up to the page above. */
private fun DrawScope.drawMarker(
    color: Color,
    down: Boolean,
) {
    drawDash(color)
    val x = size.width / 2
    val sign = if (down) 1f else -1f
    val y0 = size.height / 2
    val tip = y0 + sign * 26.dp.toPx()
    val head = 6.dp.toPx()
    drawLine(color, Offset(x, y0), Offset(x, tip), strokeWidth = 2.5.dp.toPx())
    val arrow =
        Path().apply {
            moveTo(x - head, tip - sign * head * 1.4f)
            lineTo(x, tip)
            lineTo(x + head, tip - sign * head * 1.4f)
        }
    drawPath(arrow, color, style = Stroke(2.5.dp.toPx()))
}

/**
 * The scroll indicator on the right edge: it shows how far through the PDF the list is, and can be grabbed and dragged to
 * scan quickly. While it is held, a bubble to its left says which page it is at.
 */
@Composable
private fun BoxScope.ScrollThumb(
    state: LazyListState,
    total: Int,
    footerPx: Float,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
    val info = state.layoutInfo
    val pagePx =
        info.visibleItemsInfo
            .firstOrNull { it.index < total }
            ?.size
            ?.toFloat() ?: 0f
    val viewportPx = info.viewportSize.height.toFloat()
    val maxScroll = (total * pagePx + footerPx - viewportPx).coerceAtLeast(1f)
    val scrolled = state.firstVisibleItemIndex.coerceAtMost(total) * pagePx + state.firstVisibleItemScrollOffset
    val shown = if (dragging) dragFraction else (scrolled / maxScroll).coerceIn(0f, 1f)

    val top = (shown * (trackHeight - thumbPx).coerceAtLeast(0f)).roundToInt()

    fun dragTo(y: Float) {
        val fraction = ((y - thumbPx / 2) / (trackHeight - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
        dragFraction = fraction
        if (pagePx > 0f) {
            val target = fraction * maxScroll
            val index = (target / pagePx).toInt().coerceAtMost(total)
            scope.launch { state.scrollToItem(index, (target - index * pagePx).toInt().coerceAtLeast(0)) }
        }
    }
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .width(THUMB_WIDTH)
            .fillMaxHeight()
            .onSizeChanged { trackHeight = it.height }
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragTo(it.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragTo(change.position.y)
                    },
                )
            }.semantics { contentDescription = "Scroll through the pages" },
    ) {
        val track = MaterialTheme.colorScheme.outlineVariant
        val grip = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxSize()) {
            drawLine(track, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2.dp.toPx())
            val corner = CornerRadius(8.dp.toPx())
            val thumbTop = top.toFloat()
            drawRoundRect(Color(0xFFBDBDBD), Offset(4.dp.toPx(), thumbTop), Size(size.width - 8.dp.toPx(), thumbPx), corner)
            repeat(3) {
                val y = thumbTop + thumbPx / 2 + (it - 1) * 6.dp.toPx()
                drawLine(grip, Offset(size.width / 2 - 6.dp.toPx(), y), Offset(size.width / 2 + 6.dp.toPx(), y), strokeWidth = 2.dp.toPx())
            }
        }
    }
    if (dragging) {
        val page = pageUnderThumb(dragFraction, dragFraction * maxScroll, viewportPx, pagePx, total)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-(THUMB_WIDTH.roundToPx() + 8.dp.roundToPx()), top) },
        ) {
            Text(
                "Page $page",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
