package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ParagraphPlayerTest {
    private class FakeOutput : SpeechOutput {
        val spoken = mutableListOf<Pair<String, String>>()
        var stops = 0

        val lastId get() = spoken.last().second

        override fun speak(
            text: String,
            utteranceId: String,
        ) {
            spoken += text to utteranceId
        }

        override fun stop() {
            stops++
        }
    }

    private val output = FakeOutput()
    private val player = ParagraphPlayer(listOf("one", "two", "three"), output) {}

    private fun spokenTexts() = output.spoken.map { it.first }

    @Test
    fun `plays paragraphs in order, one utterance each, then stops at the start`() {
        player.toggle()
        player.onUtteranceDone(output.lastId)
        player.onUtteranceDone(output.lastId)
        assertEquals(listOf("one", "two", "three"), spokenTexts())
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 2), player.state)

        player.onUtteranceDone(output.lastId)
        assertEquals(PlayerState(PlaybackStatus.STOPPED, 0), player.state)
    }

    @Test
    fun `pause stops speech and resume repeats the paragraph`() {
        player.toggle()
        player.onUtteranceDone(output.lastId)
        player.toggle()
        assertEquals(PlayerState(PlaybackStatus.PAUSED, 1), player.state)
        assertEquals(1, output.stops)

        player.toggle()
        assertEquals(listOf("one", "two", "two"), spokenTexts())
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 1), player.state)
    }

    @Test
    fun `a finish reported for a paused or replaced utterance is ignored`() {
        player.toggle()
        val stale = output.lastId
        player.toggle()
        player.onUtteranceDone(stale)
        assertEquals(PlayerState(PlaybackStatus.PAUSED, 0), player.state)

        player.toggle()
        player.onUtteranceDone(stale)
        assertEquals(listOf("one", "one"), spokenTexts())
    }

    @Test
    fun `an error stops playback where it was`() {
        player.toggle()
        player.onUtteranceDone(output.lastId)
        player.onUtteranceError(output.lastId)
        assertEquals(PlayerState(PlaybackStatus.STOPPED, 1), player.state)
    }

    @Test
    fun `nothing to read does nothing`() {
        val empty = ParagraphPlayer(emptyList(), output) {}
        empty.toggle()
        assertEquals(PlayerState(), empty.state)
        assertEquals(0, output.spoken.size)
    }
}
