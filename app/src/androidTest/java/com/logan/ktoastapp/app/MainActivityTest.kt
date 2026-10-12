package com.logan.ktoastapp.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.logan.ktoastapp.MainActivity
import com.logan.ktoastapp.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    val rule = activityScenarioRule<MainActivity>()

    @get:Rule
    val rules = RuleChain.outerRule(rule).around(WindowFailureDiagnosticsRule())

    @Test
    fun launchMainActivity_keepsScenarioStable() {
        rule.scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun launchMainActivity_showsScenarioCardsAndDashboard() {
        onView(withId(R.id.tvStatusPermission)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText(R.string.demo_card_smoke_title)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnJava)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnCancelAll)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun clickBasic_updatesDashboardState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        onView(withId(R.id.btnBasic)).perform(scrollTo()).check(matches(isDisplayed()))
        rule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val viewport = activity.findViewById<View>(R.id.scrollContent)
            val viewportLocation = IntArray(2).also(viewport::getLocationOnScreen)
            val decorLocation = IntArray(2).also(decor::getLocationOnScreen)
            assertTrue("Demo viewport overlaps navigation bar",
                viewportLocation[1] + viewport.height <= decorLocation[1] + decor.height - insets.bottom)
        }
        onView(withId(R.id.btnBasic)).perform(click())

        onView(withId(R.id.tvLastAction)).check(
            matches(
                withText(
                    context.getString(
                        R.string.demo_status_action_template,
                        context.getString(R.string.demo_basic_toast)
                    )
                )
            )
        )
        onView(withId(R.id.tvStatusGuide)).check(
            matches(
                withText(
                    context.getString(
                        R.string.demo_status_guide_template,
                        context.getString(R.string.demo_guide_try_style)
                    )
                )
            )
        )
    }
}
