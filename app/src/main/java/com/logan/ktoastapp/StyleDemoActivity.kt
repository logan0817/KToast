package com.logan.ktoastapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.logan.ktoast.KToastScope
import com.logan.ktoast.config.KToastStyle
import com.logan.ktoast.config.KToastType

/** 复用同一个页面作用域，展示语义、品牌资源及调用方覆盖的优先级。 */
class StyleDemoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = DemoPage(this, R.string.demo_style_title)
        val scope = KToastScope.create(this)
        DemoPage.addHero(page.content, R.string.demo_style_hero_badge,
            R.string.demo_style_title, R.string.demo_style_description)
        val semantics = DemoPage.addSection(page.content, R.string.demo_style_semantic_badge,
            R.string.demo_style_semantic_title, R.string.demo_style_semantic_description)
        val samples = listOf(
            KToastType.NORMAL to R.string.demo_style_normal,
            KToastType.SUCCESS to R.string.demo_style_success,
            KToastType.ERROR to R.string.demo_style_error,
            KToastType.WARNING to R.string.demo_style_warning,
            KToastType.INFO to R.string.demo_style_info
        )
        samples.forEach { (type, label) ->
            val buttonStyle = when (type) {
                KToastType.SUCCESS -> R.style.DemoButton_Primary
                KToastType.ERROR -> R.style.DemoButton_Danger
                else -> R.style.DemoButton_Secondary
            }
            DemoPage.addButton(semantics, label, buttonStyle) {
                scope.showStyled(getString(label), KToastStyle(type))
            }
        }
        val branding = DemoPage.addSection(page.content, R.string.demo_style_brand_badge,
            R.string.demo_style_brand_title, R.string.demo_style_brand_description, alternate = true)
        val brand = KToastStyle(KToastType.SUCCESS, R.style.KToastDemoBrand)
        DemoPage.addButton(branding, R.string.demo_style_brand, R.style.DemoButton_Primary) {
            scope.showStyled(getString(R.string.demo_style_brand), brand)
        }
        DemoPage.addButton(branding, R.string.demo_style_override) {
            scope.showStyled(getString(R.string.demo_style_override), brand) { backgroundRadius = 28f }
        }
    }
}
