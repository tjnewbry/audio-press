package io.github.tjnewbry.audiopress.app.ui.pdfimport

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tjnewbry.audiopress.app.PageRange
import io.github.tjnewbry.audiopress.app.PdfPreviews
import io.github.tjnewbry.audiopress.app.PendingImport
import io.github.tjnewbry.audiopress.app.correctTyped
import io.github.tjnewbry.audiopress.app.defaultImportName
import io.github.tjnewbry.audiopress.app.formatPages
import io.github.tjnewbry.audiopress.app.importName
import io.github.tjnewbry.audiopress.app.markAt
import io.github.tjnewbry.audiopress.app.needsNameConfirmation
import io.github.tjnewbry.audiopress.app.settle
import io.github.tjnewbry.audiopress.app.typeEnd
import io.github.tjnewbry.audiopress.app.typeStart
import io.github.tjnewbry.audiopress.app.ui.ChoiceDialog
import io.github.tjnewbry.audiopress.app.ui.DialogChoice
import io.github.tjnewbry.audiopress.app.ui.RenameDialog
import io.github.tjnewbry.audiopress.app.ui.Spacing

private val NUMBER_BOX_MIN_WIDTH = 72.dp
private val NUMBER_BOX_CORNER = 6.dp
private val NUMBER_BOX_PADDING_X = 10.dp

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
        ImportHeader(
            name,
            onCancel = onCancel,
            onRename = {
                interacted = true
                renaming = true
            },
            onImport = { if (needsNameConfirmation(interacted, typedName)) confirming = true else onImport(range.toIntRange(), name) },
        )
        // Everything below the header counts as using the screen; the Import button above does not.
        Column(Modifier.fillMaxSize().noteTouches { interacted = true }) {
            val length: @Composable () -> Unit = { Text("PDF length: ${formatPages(total)}.") }
            val rangeRow: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraLarge), verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = Spacing.Small),
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
        ChoiceDialog(
            title = "Import as \"$name\"?",
            choices =
                listOf(
                    DialogChoice("Import") { onImport(range.toIntRange(), name) },
                    DialogChoice("Rename") { renaming = true },
                    DialogChoice("Cancel") {},
                ),
            onDismiss = { confirming = false },
        )
    }
}

/** Cancel on the left, the book's [name] in the middle (tap to rename), Import on the right. */
@Composable
private fun ImportHeader(
    name: String,
    onCancel: () -> Unit,
    onRename: () -> Unit,
    onImport: () -> Unit,
) {
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
                    .clickable(onClick = onRename)
                    .padding(Spacing.Small)
                    .semantics { contentDescription = "Book name: $name. Tap to rename." },
        )
        TextButton(onClick = onImport) { Text("Import") }
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

/** A small box for a page number, outlined in red ([error]) while what is typed is not the number in use. */
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
                .widthIn(min = NUMBER_BOX_MIN_WIDTH)
                .width(IntrinsicSize.Min)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) onLeave()
                    hadFocus = it.isFocused
                }.semantics { contentDescription = description },
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(1.dp, border, RoundedCornerShape(NUMBER_BOX_CORNER))
                    .padding(horizontal = NUMBER_BOX_PADDING_X, vertical = Spacing.Small),
            ) { inner() }
        },
    )
}
