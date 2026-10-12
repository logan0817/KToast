package com.logan.ktoast.strategy

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import com.logan.ktoast.KToastScopeOptions

internal data class ToastWindowBounds(
    val width: Int, val height: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int
)

internal object ToastWindowGeometry {
    fun resolve(host: Activity, options: KToastScopeOptions): ToastWindowBounds {
        val decor = host.window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = host.windowManager.currentWindowMetrics
            val types = (if (options.avoidSystemBars) WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() else 0) or
                (if (options.avoidIme) WindowInsets.Type.ime() else 0)
            val insets = (decor.rootWindowInsets ?: metrics.windowInsets).getInsets(types)
            return ToastWindowBounds(metrics.bounds.width(), metrics.bounds.height(), insets.left, insets.top, insets.right, insets.bottom)
        }
        val width = decor.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        if (!options.avoidSystemBars && !options.avoidIme) return ToastWindowBounds(width, height, 0, 0, 0, 0)
        val visible = Rect()
        decor.getWindowVisibleDisplayFrame(visible)
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val rootInsets = if (Build.VERSION.SDK_INT >= 23) decor.rootWindowInsets else null
        @Suppress("DEPRECATION")
        val left = if (options.avoidSystemBars) rootInsets?.systemWindowInsetLeft ?: (visible.left - location[0]).coerceAtLeast(0) else 0
        @Suppress("DEPRECATION")
        val top = if (options.avoidSystemBars) rootInsets?.systemWindowInsetTop ?: (visible.top - location[1]).coerceAtLeast(0) else 0
        @Suppress("DEPRECATION")
        val right = if (options.avoidSystemBars) rootInsets?.systemWindowInsetRight ?: (location[0] + width - visible.right).coerceAtLeast(0) else 0
        @Suppress("DEPRECATION")
        val bottom = if (options.avoidIme) (location[1] + height - visible.bottom).coerceAtLeast(0)
        else if (options.avoidSystemBars) rootInsets?.stableInsetBottom ?: 0 else 0
        return ToastWindowBounds(width, height, left, top, right, bottom)
    }
}
