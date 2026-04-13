package com.logan.ktoast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KToastConfigTest {

    @Test
    fun defaults_keepVisualOptionsBackwardCompatible() {
        val config = KToastConfig()

        assertEquals(4, config.maxLines)
        assertEquals(0.8f, config.maxWidthRatio)
        assertEquals(0, config.minWidth)
        assertEquals(0, config.minHeight)
        assertEquals(0, config.borderWidth)
        assertNull(config.borderColor)
        assertNull(config.contentFactory)
    }
}
