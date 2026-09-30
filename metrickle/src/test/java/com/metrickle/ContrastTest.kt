package com.metrickle

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reference values from `contrastRatio` / `textOn` in packages/schema/src/constants.ts. */
class ContrastTest {
    @Test
    fun matchesSchema() {
        assertEquals(4.832526462804384, Contrast.ratio("#1f6fcf", "#fcfcfb"), 1e-12)
        assertEquals(4.478089453577214, Contrast.ratio("#777777", "#ffffff"), 1e-12)
        assertEquals(11.51940210868947, Contrast.ratio("#ffcc00", "#1a1a19"), 1e-12)
        assertEquals(21.0, Contrast.ratio("#000000", "#ffffff"), 1e-12)
        assertEquals("#ffffff", Contrast.textOn("#1f6fcf"))
        assertEquals("#000000", Contrast.textOn("#777777"))
        assertEquals("#000000", Contrast.textOn("#ffcc00"))
        assertEquals("#1f6fcf", Contrast.hex(0xff1f6fcf.toInt()))
    }
}
