package org.rampart

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real message list, drawn with fifty thousand rows, composes only what is on screen.
 *
 * Every row the list composes looks its message up in `scheduled`, so a map that counts
 * lookups counts composed rows exactly, without reaching inside the list. Drawn offscreen
 * the way the screenshot tests are, so it runs on a build machine with no display.
 */
class ListWindowTest {
    @Test
    fun `a fifty thousand row folder composes only the rows on screen`() {
        val start = Instant.parse("2026-09-29T12:00:00Z")
        val rows = List(50_000) { n ->
            Summary(
                "m$n", "Sender ${n % 200}", "sender${n % 200}@example.org", "Weekly update $n",
                start.minusSeconds(n * 60L).toString(), "A preview of message $n.", n % 3 != 0,
            )
        }
        var composed = 0
        val counting = object : AbstractMap<String, Long>() {
            override val entries: Set<Map.Entry<String, Long>> = emptySet()
            override fun get(key: String): Long? {
                composed++
                return null
            }
        }
        if (ListLayoutState.layout != ListLayout.NORMAL) ListLayoutState.choose(ListLayout.NORMAL)
        var askedForMore = 0
        val theme = THEMES.first()
        val began = System.nanoTime()
        renderComposeScene(900, 900) {
            CompositionLocalProvider(LocalRampartTheme provides theme, LocalIconPack provides LineIcons) {
                MaterialTheme(colorScheme = theme.scheme(), typography = RampartTypography) {
                    Surface(Modifier.fillMaxSize()) {
                        MessageList(
                            rows, null, loading = false, title = "Inbox",
                            scheduled = counting,
                            onNeedMore = { askedForMore++ },
                        ) { _, _, _ -> }
                    }
                }
            }
        }
        val ms = (System.nanoTime() - began) / 1_000_000
        println("\nMessage list, 50,000 rows held, 900 px tall: $composed rows composed, first frame in $ms ms")
        assertTrue(composed in 1..60, "the list composed $composed rows for one screen")
        assertEquals(0, askedForMore, "the top of a long folder does not ask for the next page")
    }
}
