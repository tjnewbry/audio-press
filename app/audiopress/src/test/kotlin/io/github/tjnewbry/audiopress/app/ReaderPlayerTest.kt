package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReaderPlayerTest {
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
    private var now = 0L
    private val saved = mutableListOf<Position>()
    private val measured = mutableListOf<Pair<Int, Double>>()

    // Paragraph 0 is "one two three", paragraph 1 is "four five", paragraph 2 is "six seven eight nine".
    private val utterances = splitIntoUtterances(listOf("one two three", "four five", "six seven eight nine"))

    private fun player(start: Position? = null) =
        ReaderPlayer(
            utterances,
            output,
            start,
            clock = { now },
            onMeasured = { words, seconds -> measured += words to seconds },
            onSave = { saved += it },
        )

    private fun spokenTexts() = output.spoken.map { it.first }

    @Test
    fun `plays utterances in order, then stops at the start of the book`() {
        val player = player()
        player.toggle()
        player.onUtteranceDone(output.lastId)
        player.onUtteranceDone(output.lastId)
        assertEquals(listOf("one two three", "four five", "six seven eight nine"), spokenTexts())
        assertEquals(PlaybackStatus.PLAYING, player.state.status)
        assertEquals(2, player.state.utterance)

        player.onUtteranceDone(output.lastId)
        assertEquals(PlayerState(), player.state)
        assertEquals(Position(0, 0), saved.last())
    }

    @Test
    fun `the current word follows the ranges the engine reports`() {
        val player = player()
        player.play()
        assertEquals(0 until 3, player.state.wordStart until player.state.wordEnd)

        player.onRange(output.lastId, 4, 7)
        assertEquals(4, player.state.wordStart)
        assertEquals(7, player.state.wordEnd)
        assertEquals(Position(0, 4), player.position)
    }

    @Test
    fun `pause stops speech and resume continues from the current word`() {
        val player = player()
        player.play()
        player.onRange(output.lastId, 4, 7)
        player.pause()
        assertEquals(PlaybackStatus.PAUSED, player.state.status)
        assertEquals(1, output.stops)

        player.play()
        assertEquals("two three", output.spoken.last().first)
        // The engine now reports offsets in the shorter text; they map back to the whole utterance.
        player.onRange(output.lastId, 4, 9)
        assertEquals(8, player.state.wordStart)
        assertEquals(13, player.state.wordEnd)
    }

    @Test
    fun `a finish reported for a paused or replaced utterance is ignored`() {
        val player = player()
        player.play()
        val stale = output.lastId
        player.pause()
        player.onUtteranceDone(stale)
        assertEquals(PlaybackStatus.PAUSED, player.state.status)
        assertEquals(0, player.state.utterance)

        player.play()
        player.onUtteranceDone(stale)
        assertEquals(2, output.spoken.size)
    }

    @Test
    fun `tapping a paragraph starts reading from it, whether playing or not`() {
        val player = player()
        player.jumpTo(Position(2, 0))
        assertEquals("six seven eight nine", output.spoken.last().first)
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 2, 0, 3), player.state)

        player.jumpTo(Position(1, 0))
        assertEquals("four five", output.spoken.last().first)
        assertEquals(1, player.state.utterance)
        assertNotEquals(output.spoken[0].second, output.lastId)

        player.onUtteranceDone(output.spoken[0].second) // the replaced utterance finishing must do nothing
        assertEquals(1, player.state.utterance)
    }

    @Test
    fun `a book with saved progress starts paused at that word`() {
        val player = player(Position(paragraph = 2, offset = 4))

        assertEquals(PlaybackStatus.PAUSED, player.state.status)
        assertEquals(2, player.state.utterance)
        assertEquals(4, player.state.wordStart)
        player.play()
        assertEquals("seven eight nine", output.spoken.last().first)
    }

    @Test
    fun `no saved progress, or the very start, begins stopped`() {
        assertEquals(PlayerState(), player(null).state)
        assertEquals(PlayerState(), player(Position(0, 0)).state)
    }

    @Test
    fun `a saved position past the end of the book is clamped`() {
        val player = player(Position(paragraph = 99, offset = 5))

        assertEquals(0, player.state.utterance)
    }

    @Test
    fun `progress is saved when an utterance starts, on pause, and at most every few seconds while reading`() {
        val player = player()
        player.play()
        assertEquals(listOf(Position(0, 0)), saved)

        now = 1_000
        player.onRange(output.lastId, 4, 7) // too soon after the last save
        assertEquals(1, saved.size)

        now = ReaderPlayer.SAVE_INTERVAL_MS + 1
        player.onRange(output.lastId, 8, 13)
        assertEquals(Position(0, 8), saved.last())

        player.pause()
        assertEquals(3, saved.size)
    }

    @Test
    fun `finished utterances report how long they took, but a resumed one does not`() {
        val player = player()
        player.play()
        now = 100
        player.onUtteranceStart(output.lastId)
        now = 1_600
        player.onUtteranceDone(output.lastId)
        assertEquals(listOf(3 to 1.5), measured)

        now = 2_000
        player.onUtteranceStart(output.lastId)
        player.onRange(output.lastId, 5, 9) // now on "five"
        player.pause()
        player.play() // speaks the rest of paragraph 1 only
        player.onUtteranceStart(output.lastId)
        now = 3_000
        player.onUtteranceDone(output.lastId)
        assertEquals(1, measured.size)
    }

    @Test
    fun `an error pauses at the current position`() {
        val player = player()
        player.play()
        player.onUtteranceDone(output.lastId)
        player.onUtteranceError(output.lastId)

        assertEquals(PlayerState(PlaybackStatus.PAUSED, 1, 0, 4), player.state)
    }

    @Test
    fun `nothing to read does nothing`() {
        val empty = ReaderPlayer(emptyList(), output)
        empty.toggle()
        empty.jumpTo(Position(0, 0))

        assertEquals(PlayerState(), empty.state)
        assertEquals(0, output.spoken.size)
    }
}
