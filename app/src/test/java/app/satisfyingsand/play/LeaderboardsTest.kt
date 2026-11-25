package app.satisfyingsand.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaderboardsTest {
    @Test
    fun onlyMediumEightColorsIsRanked() {
        assertTrue(Leaderboards.isRanked("medium", 8))
        assertFalse(Leaderboards.isRanked("medium", 4))
        assertFalse(Leaderboards.isRanked("fine", 8))
    }

    @Test
    fun totalNeedsEveryPictureAndIsInMilliseconds() {
        val names = listOf("A", "B", "C")
        assertNull("missing a picture -> no total", Leaderboards.totalMillis(names) { if (it == "B") null else 10f })
        assertEquals(10_000L + 20_500L + 61_250L, Leaderboards.totalMillis(names) { mapOf("A" to 10f, "B" to 20.5f, "C" to 61.25f)[it] })
    }

    @Test
    fun timesAreSubmittedInMilliseconds() {
        // Play's "Time" format reads scores as ms: 66032 is displayed as 1:06.03.
        assertEquals(66_032L, Leaderboards.toMillis(66.032f))
        assertEquals(83_456L, Leaderboards.toMillis(83.456f)) // Float is 83.45599..., must round
        assertEquals(1_000L, Leaderboards.toMillis(1f))
        assertEquals(3_600_000L, Leaderboards.toMillis(3600f))
        assertEquals("never zero", 1L, Leaderboards.toMillis(0f))
    }
}
