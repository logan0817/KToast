package com.logan.ktoast.compose

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logan.ktoast.config.KToastConfig
import com.logan.ktoast.config.KToastStyle

/** 无窗口和初始化副作用的默认内容，可直接用于 Preview；样式优先级与 View 入口一致。 */
@Composable
fun KToastContent(
    message: CharSequence,
    modifier: Modifier = Modifier,
    style: KToastStyle = KToastStyle(),
    config: KToastConfig = KToastConfig()
) {
    val resolved = style.resolve(LocalContext.current, config)
    val description = resolved.contentDescription?.toString()
    val shape = RoundedCornerShape(resolved.backgroundRadius.coerceAtLeast(0f).dp)
    val border = resolved.borderColor?.takeIf { resolved.borderWidth > 0 }?.let {
        Modifier.border(resolved.borderWidth.dp, Color(it), shape)
    } ?: Modifier
    Row(
        modifier = modifier
            .widthIn(min = resolved.minWidth.coerceAtLeast(0).dp)
            .heightIn(min = resolved.minHeight.coerceAtLeast(0).dp)
            .background(Color(resolved.backgroundColor), shape)
            .then(border)
            .padding(resolved.paddingHorizontal.coerceAtLeast(0).dp, resolved.paddingVertical.coerceAtLeast(0).dp)
            .semantics(mergeDescendants = true) {
                description?.let { contentDescription = it }
            },
        horizontalArrangement = Arrangement.spacedBy(resolved.iconPadding.coerceAtLeast(0).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        resolved.icon?.let { icon ->
            // 品牌资源允许 shape/selector 等 Drawable，不能把 View 入口的图标契约缩为 bitmap/vector。
            AndroidView(
                factory = { context -> ImageView(context).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                update = { image ->
                    image.setImageResource(icon)
                    image.contentDescription = null
                    image.clearColorFilter()
                    resolved.iconColor?.let { image.setColorFilter(it) }
                },
                modifier = Modifier.size(resolved.iconSize.coerceAtLeast(0f).dp),
            )
        }
        BasicText(
            text = message.toString(),
            style = TextStyle(color = Color(resolved.textColor), fontSize = resolved.textSize.coerceAtLeast(1f).sp),
            maxLines = resolved.maxLines.coerceAtLeast(1),
            overflow = TextOverflow.Ellipsis
        )
    }
}
