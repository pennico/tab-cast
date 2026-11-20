// SPDX-License-Identifier: GPL-3.0-or-later
package dk.pennico.tabcast.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class CalcTest {
    @Test fun samples() {
        val s = Calc.Samples()
        assertEquals(mapOf("n" to 0), s.summary())
        for (i in 1..100) s.add(i.toDouble())
        val m = s.summary()
        assertEquals(50.5, m["mean"])
        assertEquals(50.0, m["p50"])
        assertEquals(95.0, m["p95"])
        assertEquals(100.0, m["max"])
        assertEquals("mean 50.5 · p50 50.0 · p95 95.0 · max 100.0 ms (n=100)", s.text())
    }

    @Test fun cssToCopy() {
        // 2048x1536 window, WebView at (0, 0), dpr 2, video at CSS (20, 154) 635x357, copied into 960x720
        val b = Calc.cssToCopy(20.0, 154.0, 635.0, 357.0, 2.0, 0, 0, 2048, 1536, 960, 720, inset = 0.0)
        assertEquals(Calc.Box(19, 144, 595, 335), b)
        val inner = Calc.cssToCopy(20.0, 154.0, 635.0, 357.0, 2.0, 0, 0, 2048, 1536, 960, 720)
        assertEquals(Calc.Box(78, 178, 476, 268), inner)
        // view offset (status bar) moves it down
        assertEquals(144 + 30, Calc.cssToCopy(20.0, 154.0, 635.0, 357.0, 2.0, 0, 64, 2048, 1536, 960, 720, 0.0).y)
    }

    @Test fun fit() {
        assertEquals(960 to 720, Calc.fit(2048, 1536, 1280, 720))
        assertEquals(404 to 720, Calc.fit(1536, 2736, 1280, 720))
        assertEquals(1280 to 720, Calc.fit(1920, 1080, 1280, 720))
    }

    @Test fun nalTypes() {
        val b = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9, 0, 0, 1, 0x41, 7)
        assertEquals(listOf(7, 8, 5, 1), Calc.nalTypes(b))
    }

    @Test fun cpu() {
        assertEquals(150, Calc.cpuPercent(1500, 1000))
        assertEquals(0, Calc.cpuPercent(5, 0))
    }
}
