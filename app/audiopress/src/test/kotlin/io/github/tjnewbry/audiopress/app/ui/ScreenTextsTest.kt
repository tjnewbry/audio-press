package io.github.tjnewbry.audiopress.app.ui

import io.github.tjnewbry.audiopress.app.BakeState
import io.github.tjnewbry.audiopress.app.VoiceChoice
import io.github.tjnewbry.audiopress.app.VoiceQuality
import io.github.tjnewbry.audiopress.app.ui.bake.bakingTitle
import io.github.tjnewbry.audiopress.app.ui.bake.remainingText
import io.github.tjnewbry.audiopress.app.ui.library.bookSummary
import io.github.tjnewbry.audiopress.app.ui.player.bakeButtonLabel
import io.github.tjnewbry.audiopress.app.ui.player.bakingLine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts the screens build from state, word for word as tjnewbry specified them. */
class ScreenTextsTest {
    private val bella = VoiceChoice("af_bella", VoiceQuality.MEDIUM)

    @Test
    fun `the bake button offers to bake, or to resume an interrupted bake`() {
        assertEquals("Bake audio for this book", bakeButtonLabel(BakeState.NotBaked(0, 18)))
        assertEquals("Resume baking (3 of 18 done)", bakeButtonLabel(BakeState.NotBaked(3, 18)))
    }

    @Test
    fun `the player names the voice while baking`() {
        assertEquals("Baking audio using Bella: 3 of 18", bakingLine(BakeState.Baking(3, 18, voice = bella)))
        assertEquals("Baking audio: 3 of 18", bakingLine(BakeState.Baking(3, 18)))
    }

    @Test
    fun `the bake page shows the voice and the time left`() {
        assertEquals("Baking audio using Bella...", bakingTitle(BakeState.Baking(1, 7, voice = bella)))
        assertEquals("Baking audio...", bakingTitle(BakeState.Baking(1, 7)))
        assertEquals("Cancelling...", bakingTitle(BakeState.Baking(1, 7, cancelling = true, voice = bella)))

        assertEquals("Working out how long it will take...", remainingText(BakeState.Baking(0, 7)))
        assertEquals("about 4 min left", remainingText(BakeState.Baking(1, 7, etaMs = 4 * 60_000L)))
        assertEquals("", remainingText(BakeState.Baking(1, 7, cancelling = true, etaMs = 4 * 60_000L)))
    }

    @Test
    fun `a library row gives the word count and import date`() {
        val format = SimpleDateFormat("MMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val nov2 = 1_793_577_600_000L // 2026-11-02 00:00 UTC
        assertEquals("4,140 words, imported Nov 2, 2026", bookSummary(4140, nov2, format, Locale.US))
    }
}
