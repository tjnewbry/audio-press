package io.github.tjnewbry.audiopress.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BakeNoticeTest {
    private val santa = VoiceChoice("am_santa", VoiceQuality.LITE)

    @Test
    fun `a download shows its quality and percent`() {
        val notice = assertNotNull(noticeFor("My Book", BakeState.Downloading(VoiceQuality.LITE, 0.426f)))

        assertEquals("My Book", notice.title)
        assertEquals("Downloading Lite...", notice.text)
        assertEquals(43, notice.done)
        assertEquals(100, notice.total)
        assertEquals(false, notice.indeterminate)
    }

    @Test
    fun `baking shows the voice and how many clips are done`() {
        val notice = assertNotNull(noticeFor("My Book", BakeState.Baking(12, 50, voice = santa)))

        assertEquals("Baking audio using Santa...", notice.text)
        assertEquals(12, notice.done)
        assertEquals(50, notice.total)
        assertEquals("Baking audio...", noticeFor("My Book", BakeState.Baking(0, 50))?.text)
    }

    @Test
    fun `cancelling says so in both stages`() {
        assertEquals("Cancelling...", noticeFor("b", BakeState.Downloading(VoiceQuality.HEAVY, 0.1f, cancelling = true))?.text)
        val baking = assertNotNull(noticeFor("b", BakeState.Baking(1, 5, cancelling = true, voice = santa)))
        assertEquals("Cancelling...", baking.text)
        assertTrue(baking.cancelling)
    }

    @Test
    fun `a bake with nothing to count has an indeterminate bar, and states that are not running have no notice`() {
        assertEquals(true, noticeFor("b", BakeState.Baking(0, 0))?.indeterminate)
        assertNull(noticeFor("b", BakeState.Baked(5)))
        assertNull(noticeFor("b", BakeState.NotBaked(0, 5)))
        assertNull(noticeFor("b", BakeState.Failed("no", 0, 5)))
        assertNull(noticeFor("b", BakeState.Unavailable))
    }

    @Test
    fun `the tracker reports the running bake, and none once it ends`() {
        val shown = mutableListOf<String?>()
        val tracker = ActiveBakeTracker { shown += it?.bookId }

        tracker.update("a", "Book A", BakeState.Downloading(VoiceQuality.LITE, 0f))
        tracker.update("a", "Book A", BakeState.Baking(1, 5, voice = santa))
        assertEquals("a", tracker.current?.bookId)
        assertEquals("Book A", tracker.current?.bookTitle)

        tracker.update("a", "Book A", BakeState.Baked(100))
        assertNull(tracker.current)
        assertEquals(listOf<String?>("a", "a", null), shown)
    }

    @Test
    fun `with two books baking, the first keeps the notification until it ends`() {
        val tracker = ActiveBakeTracker {}
        tracker.update("a", "A", BakeState.Baking(1, 5))
        tracker.update("b", "B", BakeState.Baking(1, 9))
        assertEquals("a", tracker.current?.bookId)

        tracker.update("a", "A", BakeState.NotBaked(3, 5))
        assertEquals("b", tracker.current?.bookId)

        tracker.update("b", "B", BakeState.Failed("x", 1, 9))
        assertNull(tracker.current)
    }
}
