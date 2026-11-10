package com.logan.ktoastapp

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isEmpty
import androidx.core.view.updatePadding
import com.logan.ktoastapp.databinding.ActivityDemoPageBinding
import com.logan.ktoastapp.databinding.ViewDemoHeroBinding
import com.logan.ktoastapp.databinding.ViewDemoSectionBinding
import kotlin.math.roundToInt

/** Demo 页面共用原生容器、系统栏和组件，页面只负责内容与操作。 */
class DemoPage(activity: AppCompatActivity, titleRes: Int) {
    val content: LinearLayout

    init {
        val binding = ActivityDemoPageBinding.inflate(activity.layoutInflater)
        content = binding.demoContent
        activity.setContentView(binding.root)
        configureWindow(activity, binding.root, binding.toolbar)
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.apply {
            setTitle(titleRes)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_demo_back)
            setHomeActionContentDescription(R.string.demo_back)
        }
        binding.toolbar.setNavigationOnClickListener { activity.finish() }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun configureWindow(activity: AppCompatActivity, root: View, toolbar: Toolbar) {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            // 旧系统不支持深色系统栏图标，沿用深色底保证白色图标可读。
            val legacyBarColor = ContextCompat.getColor(activity, R.color.colorPrimaryDark)
            window.statusBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Color.TRANSPARENT else legacyBarColor
            window.navigationBarColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Color.TRANSPARENT else legacyBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }

            val initialPadding = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            val toolbarTop = toolbar.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                // 根视口止于导航栏上方，Toolbar背景延伸到状态栏下方。
                view.updatePadding(left = initialPadding.left + insets.left,
                    top = initialPadding.top, right = initialPadding.right + insets.right,
                    bottom = initialPadding.bottom + insets.bottom)
                toolbar.updatePadding(top = toolbarTop + insets.top)
                windowInsets
            }
            ViewCompat.requestApplyInsets(root)
        }

        fun addHero(parent: LinearLayout, badgeRes: Int, titleRes: Int, descriptionRes: Int) {
            val binding = ViewDemoHeroBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.demoHeroBadge.setText(badgeRes)
            binding.demoHeroTitle.setText(titleRes)
            binding.demoHeroDescription.setText(descriptionRes)
            parent.addView(binding.root)
        }

        fun addSection(parent: LinearLayout, badgeRes: Int, titleRes: Int, descriptionRes: Int,
            alternate: Boolean = false): LinearLayout {
            val binding = ViewDemoSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            if (alternate) binding.root.setBackgroundResource(R.drawable.bg_demo_card_alt)
            binding.demoSectionBadge.setText(badgeRes)
            binding.demoSectionTitle.setText(titleRes)
            binding.demoSectionDescription.setText(descriptionRes)
            parent.addView(binding.root)
            return binding.demoSectionContent
        }

        fun addButton(parent: LinearLayout, labelRes: Int, styleRes: Int = R.style.DemoButton_Secondary,
            onClick: () -> Unit): Button {
            val layoutRes = when (styleRes) {
                R.style.DemoButton_Primary -> R.layout.view_demo_button_primary
                R.style.DemoButton_Secondary -> R.layout.view_demo_button_secondary
                R.style.DemoButton_Danger -> R.layout.view_demo_button_danger
                else -> error("Demo button requires a DemoButton style")
            }
            val button = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false) as Button
            if (parent.isEmpty()) {
                (button.layoutParams as LinearLayout.LayoutParams).topMargin = (18 * parent.resources.displayMetrics.density).roundToInt()
            }
            button.setText(labelRes)
            button.setOnClickListener { onClick() }
            parent.addView(button)
            return button
        }

        fun addStatus(parent: LinearLayout, text: String): TextView {
            val view = TextView(parent.context).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.demo_text_secondary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (10 * resources.displayMetrics.density).roundToInt()
                }
            }
            parent.addView(view)
            return view
        }
    }
}
