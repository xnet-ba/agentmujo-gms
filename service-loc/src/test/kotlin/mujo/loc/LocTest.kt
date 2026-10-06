package mujo.loc

import kotlin.test.*

class LocTest {
    @Test fun haversineSanity() {
        val d = haversineM(43.8563, 18.4131, 43.3438, 17.8078) // Sarajevo–Mostar
        assertTrue(d in 60000.0..95000.0, "očekivano ~73km, dobijeno $d")
        assertEquals(0.0, haversineM(0.0, 0.0, 0.0, 0.0))
    }

    @Test fun cachePolicy() {
        val c = PositionCache(maxAgeTicks = 30, minMoveM = 25.0)
        assertNull(c.current(0))
        assertTrue(c.update(Position(43.85, 18.35, 5f, 0), 0))
        assertFalse(c.update(Position(43.85001, 18.35001, 5f, 5), 5), "šum ~1m se odbija")
        assertTrue(c.update(Position(43.851, 18.352, 5f, 10), 10), "pomak ~200m se prima")
        assertNotNull(c.current(35))
        assertNull(c.current(41), "starije od 30 tickova → null")
    }
}
