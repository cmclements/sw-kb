package app.satisfyingsand.play

import app.satisfyingsand.ui.BuiltInImages
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every library picture (and the total) resolves to its real Play Games leaderboard ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeaderboardIdsTest {
    @Test
    fun libraryPicturesMapToTheirPlayConsoleIds() {
        val ctx = RuntimeEnvironment.getApplication()
        val expected = mapOf(
            "Sunset" to "CgkImIPfreMKEAIQAQ",
            "Mountains" to "CgkImIPfreMKEAIQAw",
            "Heart" to "CgkImIPfreMKEAIQBA",
            "Rainbow" to "CgkImIPfreMKEAIQBQ",
            "Smiley" to "CgkImIPfreMKEAIQBg",
            "Balloon" to "CgkImIPfreMKEAIQBw",
            "Mushroom" to "CgkImIPfreMKEAIQCA",
            "Night City" to "CgkImIPfreMKEAIQCQ",
        )
        // Every picture in the library has a board, and no board is shared.
        assertEquals(expected.keys, BuiltInImages.entries.map { it.name }.toSet())
        for ((name, id) in expected) assertEquals(name, id, Leaderboards.forPicture(ctx, name))
        assertEquals("CgkImIPfreMKEAIQAg", Leaderboards.total(ctx))
        assertEquals(9, (expected.values + Leaderboards.total(ctx)).toSet().size)
    }
}
