package com.logan.ktoast.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class KToastStyleTest {
    @Test fun normalPreservesEveryBaseOptionInBothModes() {
        val base = KToastConfig(textColor = 123, backgroundColor = 456, icon = 789, iconColor = 321,
            paddingHorizontal = 19, backgroundRadius = 11f)
        for (dark in listOf(false, true)) {
            val actual = base.copy()
            KToastStyle().applySemantic(actual, dark)
            assertEquals(base, actual)
        }
    }

    @Test fun semanticPalettesHaveDistinctIconsAndReadableTextInBothModes() {
        val types = KToastType.values().filter { it != KToastType.NORMAL }
        for (dark in listOf(false, true)) {
            val icons = types.map { type ->
                val config = KToastConfig(paddingHorizontal = 19, backgroundRadius = 11f)
                KToastStyle(type).applySemantic(config, dark)
                assertNotNull(config.icon)
                assertEquals(config.textColor, config.iconColor)
                assertEquals(19, config.paddingHorizontal)
                assertEquals(11f, config.backgroundRadius)
                assertTrue("$type dark=$dark must retain text contrast", contrast(config.textColor, config.backgroundColor) >= 4.5)
                config.icon
            }
            assertEquals(types.size, icons.toSet().size)
        }
    }

    @Test fun eachSemanticPaletteRespondsToNightMode() {
        for (type in KToastType.values().filter { it != KToastType.NORMAL }) {
            val light = KToastConfig()
            val dark = KToastConfig()
            KToastStyle(type).applySemantic(light, false)
            KToastStyle(type).applySemantic(dark, true)
            assertNotEquals(light.backgroundColor, dark.backgroundColor)
            assertEquals(light.icon, dark.icon)
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
