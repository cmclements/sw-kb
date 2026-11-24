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
}
