package kr.toon2reels

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class PanelDetectorTest {
    private fun grid(cols: Int, rows: Int): List<Crop> {
        val w = 600; val h = 800; val dark = BooleanArray(w*h)
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) {
            for (y in y1..y2) for (x in x1..x2) dark[y*w+x] = true
        }
        for (row in 0..rows) {
            val y = 8 + row * 780 / rows
            line(10, y, 590, y+2)
        }
        for (col in 0..cols) {
            val x = 10 + col * 580 / cols
            line(x, 8, x+2, 790)
        }
        return PanelDetector.detectGrid(dark, w, h)
    }

    @Test fun `finds variable number of cuts in reading order`() {
        for ((cols, rows) in listOf(2 to 2, 2 to 3, 2 to 4, 3 to 3, 2 to 5)) {
            val found = grid(cols, rows)
            assertEquals("$cols x $rows", cols*rows, found.size)
            assertTrue(found.zipWithNext().all { (a,b) -> b.top > a.top || b.left > a.left })
        }
    }

    @Test fun `no panel border returns empty and supports fallback`() {
        assertTrue(PanelDetector.detectGrid(BooleanArray(600*800), 600, 800).isEmpty())
    }

    @Test fun `timeline handles transitions and final frame`() {
        val uri = mock(android.net.Uri::class.java)
        val p = Project(panels = listOf(Panel(1, uri), Panel(2, uri)), transition = Transition.FADE)
        assertEquals(1, Timeline.locate(p, 2.4f).next)
        assertEquals(1, Timeline.locate(p, 3f).current)
        assertNull(Timeline.locate(p, 5f).next)
        assertEquals(5f, p.seconds)
    }

    @Test fun `separate images can be reordered without changing their crops or timing`() {
        val uri = mock(android.net.Uri::class.java)
        val cuts = listOf(Panel(1,uri,duration=1.5f), Panel(2,uri,duration=4f), Panel(3,uri,duration=2.5f))
        val reordered = PanelOps.move(cuts,2,0)
        assertEquals(listOf(3L,1L,2L), reordered.map { it.id })
        assertEquals(listOf(2.5f,1.5f,4f), reordered.map { it.duration })
        assertEquals(8f, Project(panels=reordered).seconds)
    }
}
