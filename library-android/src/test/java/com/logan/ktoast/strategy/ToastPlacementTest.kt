package com.logan.ktoast.strategy

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Test

class ToastPlacementTest {
    private val bounds = ToastWindowBounds(1000, 800, 20, 30, 40, 100)

    @Test fun offsetsAreClampedToSafeArea_withoutOverflow() {
        assertEquals(20 to 30, ToastPlacement.resolve(bounds, 200, 100, Gravity.LEFT or Gravity.TOP, Int.MIN_VALUE, Int.MIN_VALUE))
        assertEquals(760 to 600, ToastPlacement.resolve(bounds, 200, 100, Gravity.LEFT or Gravity.TOP, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(20 to 30, ToastPlacement.resolve(bounds, 200, 100, Gravity.RIGHT or Gravity.BOTTOM, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun gravityAndOffsetsUseEntireContentSize() {
        assertEquals(700 to 580, ToastPlacement.resolve(bounds, 200, 100, Gravity.RIGHT or Gravity.BOTTOM, 100, 120))
        assertEquals(420 to 320, ToastPlacement.resolve(bounds, 200, 100, Gravity.CENTER, 20, -30))
    }

    @Test fun keyboardAndWindowResizeChangePosition() {
        val withIme = bounds.copy(bottom = 400)
        assertEquals(760 to 300, ToastPlacement.resolve(withIme, 200, 100, Gravity.RIGHT or Gravity.BOTTOM, 0, 0))
        assertEquals(20 to 30, ToastPlacement.resolve(bounds.copy(width = 100, height = 100), 200, 100, Gravity.CENTER, 0, 0))
    }
}
