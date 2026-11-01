package io.github.tjnewbry.audiopress.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BakedPlaybackTest {
    private class FakeClipPlayer : ClipPlayer {
        override var onCompleted: ((Int) -> Unit)? = null
        override var onError: ((Int) -> Unit)? = null
        override var positionMs = 0
        val started = mutableListOf<Triple<String, Int, Int>>() // file name, fromMs, id

        val lastId get() = started.last().third

        override fun start(
            file: File,
            fromMs: Int,
            id: Int,
        ) {
            started += Triple(file.name, fromMs, id)
            positionMs = fromMs
        }

        override fun pause(): Int = positionMs
    }

    private class FakeOutput : SpeechOutput {
        val spoken = mutableListOf<Pair<String, String>>()

        override fun speak(
            text: String,
            utteranceId: String,
        ) {
            spoken += text to utteranceId
        }

        override fun stop() = Unit
    }

    // Paragraph 0 is one utterance, paragraph 1 is two (cut at the sentence end), paragraph 2 is one.
    private val paragraphs = listOf("one two three", "Four five six. Seven eight nine.", "ten eleven")
    private val utterances = splitIntoUtterances(paragraphs, maxChars = 20)
    private val clips = utterances.mapIndexed { i, _ -> BakedClip(File("clip$i.wav"), 1000L * (i + 1)) } // 1 s, 2 s, 3 s, 4 s
    private val clipPlayer = FakeClipPlayer()
    private val output = FakeOutput()
    private val saved = mutableListOf<Position>()

    private fun baked(start: Position? = null) = BakedPlayer(utterances, clips, clipPlayer, start, onSave = { saved += it })

    private fun live(start: Position? = null) = ReaderPlayer(utterances, output, start)

    @Test
    fun `the paragraph is cut into the utterances the clips were made from`() {
        assertEquals(4, utterances.size)
        assertEquals(listOf(0, 1, 1, 2), utterances.map { it.paragraph })
    }

    @Test
    fun `plays the clips in order and ends at the start of the book`() {
        val player = baked()
        player.play()
        player.onCompleteLast()
        player.onCompleteLast()
        player.onCompleteLast()
        assertEquals(listOf("clip0.wav", "clip1.wav", "clip2.wav", "clip3.wav"), clipPlayer.started.map { it.first })
        assertEquals(3, player.state.utterance)

        player.onCompleteLast()
        assertEquals(PlayerState(), player.state)
        assertEquals(Position(0, 0), saved.last())
    }

    private fun BakedPlayer.onCompleteLast() = clipPlayer.onCompleted?.invoke(clipPlayer.lastId)

    @Test
    fun `elapsed and total time come from the real durations`() {
        val player = baked()
        assertEquals(10_000L, player.totalMs)
        assertEquals(0L, player.elapsedMs)

        player.jumpToParagraph(1) // second clip, which starts after the first one's 1 s
        clipPlayer.positionMs = 500
        assertEquals(1500L, player.elapsedMs)

        player.jumpToParagraph(2) // fourth clip, after 1 + 2 + 3 s
        assertEquals(6000L, player.elapsedMs)
    }

    @Test
    fun `pause keeps the point in the clip and resume continues from it`() {
        val player = baked()
        player.jumpToParagraph(1)
        clipPlayer.positionMs = 1200
        player.pause()
        assertEquals(PlaybackStatus.PAUSED, player.state.status)
        assertEquals(2200L, player.elapsedMs)

        player.play()
        assertEquals(Triple("clip1.wav", 1200, clipPlayer.lastId), clipPlayer.started.last())
    }

    @Test
    fun `a completion reported for a paused or replaced clip is ignored`() {
        val player = baked()
        player.play()
        val stale = clipPlayer.lastId
        player.pause()
        clipPlayer.onCompleted?.invoke(stale)
        assertEquals(PlayerState(PlaybackStatus.PAUSED, 0), player.state)

        player.jumpToParagraph(2)
        clipPlayer.onCompleted?.invoke(stale)
        assertEquals(3, player.state.utterance)
    }

    @Test
    fun `an error pauses at the clip`() {
        val player = baked()
        player.play()
        clipPlayer.onError?.invoke(clipPlayer.lastId)

        assertEquals(PlayerState(PlaybackStatus.PAUSED, 0), player.state)
    }

    @Test
    fun `progress is saved as the start of the utterance`() {
        val player = baked()
        player.jumpToParagraph(1)
        assertEquals(Position(1, 0), saved.last())
        player.onCompleteLast()
        assertEquals(Position(1, utterances[2].start), saved.last())
    }

    @Test
    fun `a clip count that does not match the utterances is refused`() {
        val error = runCatching { BakedPlayer(utterances, clips.dropLast(1), clipPlayer) }.exceptionOrNull()

        assertNotNull(error)
    }

    @Test
    fun `a position saved in live mode resumes at the right utterance in baked mode, and back`() {
        // Live: the reader was at "Eight" in paragraph 1, which is in the second utterance of that paragraph.
        val offsetOfEight = paragraphs[1].indexOf("eight")
        val live = live(Position(1, offsetOfEight))
        val baked = baked(live.position)
        assertEquals(2, baked.state.utterance)
        assertEquals(Position(1, utterances[2].start), baked.position)

        // Baked: the reader was in the third clip; live resumes at the start of that utterance.
        baked.jumpToParagraph(2)
        val back = live(baked.position)
        assertEquals(3, back.state.utterance)
        assertEquals(0, back.state.wordStart)
    }

    @Test
    fun `switching starts baked playback at the live position, and is decided only when playback starts`() {
        var bakedPlayer: BakedPlayer? = null
        val live = live()
        val switching = SwitchingPlayback(live, { bakedPlayer })
        assertEquals(PlaybackMode.LIVE, switching.mode)

        switching.jumpToParagraph(1)
        live.onRange(output.spoken.last().second, 6, 10) // "five" in the first utterance of paragraph 1
        assertEquals(PlaybackMode.LIVE, switching.mode)

        bakedPlayer = baked() // the bake finishes while the book is being read: nothing changes yet
        assertEquals(PlaybackMode.LIVE, switching.mode)
        assertEquals(PlaybackStatus.PLAYING, switching.state.status)

        switching.toggle() // pause, still live
        assertEquals(PlaybackStatus.PAUSED, switching.state.status)
        assertEquals(PlaybackMode.LIVE, switching.mode)

        switching.toggle() // play: now it switches, carrying the position
        assertEquals(PlaybackMode.BAKED, switching.mode)
        assertEquals("clip1.wav", clipPlayer.started.last().first)
        assertEquals(PlaybackStatus.PLAYING, switching.state.status)
    }

    @Test
    fun `tapping a line while live reading switches to baked audio and plays that line`() {
        var bakedPlayer: BakedPlayer? = null
        val live = live()
        val modes = mutableListOf<PlaybackMode>()
        val switching = SwitchingPlayback(live, { bakedPlayer }, onModeChange = { modes += it })
        switching.toggle()
        bakedPlayer = baked()

        switching.jumpToParagraph(2)

        assertEquals(listOf(PlaybackMode.BAKED), modes)
        assertEquals("clip3.wav", clipPlayer.started.last().first)
        assertEquals(PlayerState(PlaybackStatus.PLAYING, 3), switching.state)
    }

    @Test
    fun `a baked book starts in baked mode`() {
        val bakedPlayer = baked(Position(2, 0))
        val switching = SwitchingPlayback(live(), { bakedPlayer })

        assertEquals(PlaybackMode.BAKED, switching.mode)
        assertEquals(PlayerState(PlaybackStatus.PAUSED, 3), switching.state) // no current word: baked audio has no word timing
    }
}
