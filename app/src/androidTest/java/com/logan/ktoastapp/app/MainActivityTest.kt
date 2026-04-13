package com.logan.ktoastapp.app

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val rule = activityScenarioRule<MainActivity>()

    @Test
    fun launchMainActivity_keepsScenarioStable() {
        rule.scenario.onActivity { activity ->
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun launchMainActivity_showsScenarioCardsAndDashboard() {
        onView(withId(R.id.tvStatusPermission)).check(matches(isDisplayed()))
        onView(withText(R.string.demo_card_smoke_title)).check(matches(isDisplayed()))
        onView(withId(R.id.btnJava)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.btnCancelAll)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun clickBasic_updatesDashboardState() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        onView(withId(R.id.btnBasic)).perform(scrollTo(), click())

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
