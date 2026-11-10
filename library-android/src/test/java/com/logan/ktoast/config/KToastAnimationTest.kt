package com.logan.ktoast.config

import android.animation.TimeInterpolator
import org.junit.Assert.*
import org.junit.Test

class KToastAnimationTest {
    @Test fun snapshotPreservesAnimationAndKeepsReassignmentsIsolated() {
        val animation = KToastAnimation(TimeInterpolator { it }, TimeInterpolator { it * it }, 1.2f)
        val config = KToastConfig().apply { this.animation = animation }
        val snapshot = config.snapshot()
        assertNotSame(config, snapshot)
        assertSame(animation, snapshot.animation)
        config.animation = KToastAnimation()
        assertSame(animation, snapshot.animation)
    }

    @Test fun phasesUseTheirOwnInterpolatorAndBoundBothScaleDirections() {
        val animation = KToastAnimation(TimeInterpolator { 10f }, TimeInterpolator { -10f }, 1.2f)
        val enter = animation.boundedInterpolator(entering = true)
        val exit = animation.boundedInterpolator(entering = false)
        for (frame in 0..100) {
            val fraction = frame / 100f
            assertEquals(1.2f, 0.8f + 0.2f * enter.getInterpolation(fraction), 0.00001f)
            assertEquals(1.2f, 1f - 0.2f * exit.getInterpolation(fraction), 0.00001f)
        }
        val linear = KToastAnimation(TimeInterpolator { it }, TimeInterpolator { it })
        assertEquals(0.25f, linear.boundedInterpolator(true).getInterpolation(0.25f), 0f)
        assertEquals(0.25f, linear.boundedInterpolator(false).getInterpolation(0.25f), 0f)
    }

    @Test fun invalidScaleAndNonFiniteInterpolatorAreRejected() {
        for (scale in listOf(0.9f, 2.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { KToastAnimation(maxScale = scale) }
        }
        val invalid = KToastAnimation(TimeInterpolator { Float.NaN })
        assertThrows(IllegalArgumentException::class.java) { invalid.boundedInterpolator(true).getInterpolation(0.5f) }
    }
}
