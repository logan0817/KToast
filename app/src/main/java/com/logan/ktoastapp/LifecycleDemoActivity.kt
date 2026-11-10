package com.logan.ktoastapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastDropReason
import com.logan.ktoast.runtime.ToastHandle

/** 场景结果留在 Activity 中，便于对照 Fragment View 销毁前后的真实请求状态。 */
class LifecycleDemoActivity : AppCompatActivity() {
    companion object {
        private const val CONTAINER_ID = 0x00a12001
        private const val CAPACITY_DELAY_MILLIS = 60_000L
    }

    private lateinit var switchButton: Button
    private lateinit var lifecycleStatus: TextView
    private lateinit var capacityStatus: TextView
    private lateinit var capacityRetryButton: Button
    private lateinit var capacityClearButton: Button
    private lateinit var expiryStatus: TextView
    private lateinit var expiryStopButton: Button
    private var delayedRequest: ToastHandle? = null
    private var delayedPage = "A"
    private var freshRequest: ToastHandle? = null
    private var freshPage = "A"
    private val capacityRequests = mutableListOf<ToastHandle>()
    private var capacityRetry: ToastHandle? = null
    private val expiryRequests = mutableListOf<ToastHandle>()

    val page: LifecycleDemoFragment
        get() = supportFragmentManager.findFragmentById(CONTAINER_ID) as LifecycleDemoFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demo = DemoPage(this, R.string.demo_lifecycle_title)
        DemoPage.addHero(demo.content, R.string.demo_lifecycle_hero_badge,
            R.string.demo_lifecycle_hero_title, R.string.demo_lifecycle_hero_description)
        addLifecycleScene(demo.content)
        addCapacityScene(demo.content)
        addExpiryScene(demo.content)
        if (savedInstanceState == null) switchPage("A")
        updateSwitchButton()
        renderLifecycle()
        renderCapacity()
        renderExpiry()
    }

    private fun addLifecycleScene(parent: LinearLayout) {
        val actions = DemoPage.addSection(parent, R.string.demo_lifecycle_scene_badge,
            R.string.demo_lifecycle_scene_title, R.string.demo_lifecycle_scene_description)
        actions.addView(FrameLayout(this).apply { id = CONTAINER_ID },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lifecycleStatus = DemoPage.addStatus(actions, "")
        lifecycleStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        DemoPage.addButton(actions, R.string.lifecycle_delayed, R.style.DemoButton_Primary) {
            page.toastScope.cancel()
            delayedPage = page.pageName
            freshRequest = null
            delayedRequest = page.toastScope.showDelayed(
                getString(R.string.lifecycle_delayed_message, delayedPage), 3_000L,
                callbacks = callbacks(::renderLifecycle))
            renderLifecycle()
        }
        switchButton = DemoPage.addButton(actions, R.string.lifecycle_switch) {
            switchPage(if (page.pageName == "A") "B" else "A")
        }
        DemoPage.addButton(actions, R.string.lifecycle_show) {
            freshPage = page.pageName
            freshRequest = page.toastScope.show(getString(R.string.lifecycle_fresh_message, freshPage),
                callbacks = callbacks(::renderLifecycle))
            renderLifecycle()
        }
        DemoPage.addButton(actions, R.string.lifecycle_rebuild) { rebuildPageView() }
        DemoPage.addStatus(actions, getString(R.string.lifecycle_rebuild_guide))
    }

    private fun addCapacityScene(parent: LinearLayout) {
        val actions = DemoPage.addSection(parent, R.string.demo_capacity_scene_badge,
            R.string.demo_capacity_scene_title, R.string.demo_capacity_scene_description,
            alternate = true)
        capacityStatus = DemoPage.addStatus(actions, "")
        capacityStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        DemoPage.addButton(actions, R.string.lifecycle_capacity, R.style.DemoButton_Primary) {
            page.toastScope.cancel()
            capacityRequests.clear()
            capacityRetry = null
            repeat(33) { index ->
                capacityRequests += page.toastScope.showDelayed(
                    getString(R.string.lifecycle_capacity_message, index + 1), CAPACITY_DELAY_MILLIS,
                    callbacks = callbacks(::renderCapacity))
            }
            renderCapacity()
        }
        capacityRetryButton = DemoPage.addButton(actions, R.string.lifecycle_capacity_retry) {
            val pending = capacityRequests.firstOrNull { it.state == ToastHandle.State.PENDING }
            if (pending != null && pending.cancel()) {
                capacityRetry = page.toastScope.showDelayed(
                    getString(R.string.lifecycle_capacity_retry_message), CAPACITY_DELAY_MILLIS,
                    callbacks = callbacks(::renderCapacity))
                renderCapacity()
            }
        }
        capacityClearButton = DemoPage.addButton(actions, R.string.lifecycle_capacity_clear, R.style.DemoButton_Danger) {
            page.toastScope.cancel()
        }
    }

    private fun addExpiryScene(parent: LinearLayout) {
        val actions = DemoPage.addSection(parent, R.string.demo_expiry_scene_badge,
            R.string.demo_expiry_scene_title, R.string.demo_expiry_scene_description)
        expiryStatus = DemoPage.addStatus(actions, "")
        expiryStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        DemoPage.addButton(actions, R.string.lifecycle_expiry, R.style.DemoButton_Primary) {
            page.toastScope.cancel()
            expiryRequests.clear()
            repeat(6) { index ->
                expiryRequests += page.toastScope.show(
                    getString(R.string.lifecycle_expiry_message, index + 1), Toast.LENGTH_LONG,
                    KToastBehavior(KToastDisplayMode.QUEUE), callbacks(::renderExpiry))
            }
            renderExpiry()
        }
        expiryStopButton = DemoPage.addButton(actions, R.string.lifecycle_expiry_stop, R.style.DemoButton_Danger) {
            page.toastScope.cancel()
        }
    }

    fun switchPage(name: String) {
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, LifecycleDemoFragment.newInstance(name)).commitNow()
        updateSwitchButton()
    }

    fun rebuildPageView() {
        val current = page
        supportFragmentManager.beginTransaction().detach(current).commitNow()
        supportFragmentManager.beginTransaction().attach(current).commitNow()
    }

    private fun updateSwitchButton() {
        switchButton.text = getString(R.string.lifecycle_switch, if (page.pageName == "A") "B" else "A")
    }

    private fun callbacks(onChange: () -> Unit) = KToastCallbacks(
        onShow = { onChange() },
        onDismiss = { _, _ -> onChange() }
    )

    private fun renderLifecycle() {
        val delayed = delayedRequest?.let {
            getString(R.string.lifecycle_delayed_result, delayedPage, stateLabel(it))
        } ?: getString(R.string.lifecycle_delayed_idle)
        val fresh = freshRequest?.let {
            getString(R.string.lifecycle_fresh_result, freshPage, stateLabel(it))
        } ?: getString(R.string.lifecycle_fresh_idle)
        lifecycleStatus.text = getString(R.string.lifecycle_live_result, delayed, fresh)
    }

    private fun renderCapacity() {
        val all = capacityRequests + listOfNotNull(capacityRetry)
        capacityStatus.text = if (capacityRequests.isEmpty()) {
            getString(R.string.lifecycle_capacity_idle)
        } else {
            val rejected = capacityRequests.count { it.dropReason == ToastDropReason.CAPACITY_EXCEEDED }
            getString(R.string.lifecycle_capacity_result,
                capacityRequests.size, capacityRequests.size - rejected, rejected,
                all.count { it.state == ToastHandle.State.PENDING },
                all.count { it.isCanceled && it.dropReason == null },
                capacityRetry?.let { stateLabel(it) } ?: getString(R.string.lifecycle_retry_idle))
        }
        capacityRetryButton.isEnabled = capacityRetry == null && capacityRequests.any { it.state == ToastHandle.State.PENDING }
        capacityClearButton.isEnabled = all.any { !it.isFinished }
    }

    private fun renderExpiry() {
        expiryStatus.text = if (expiryRequests.isEmpty()) {
            getString(R.string.lifecycle_expiry_idle)
        } else {
            expiryRequests.mapIndexed { index, handle ->
                getString(R.string.lifecycle_expiry_result, index + 1, stateLabel(handle))
            }.joinToString("\n")
        }
        expiryStopButton.isEnabled = expiryRequests.any { !it.isFinished }
    }

    private fun stateLabel(handle: ToastHandle): String = getString(when {
        handle.dropReason == ToastDropReason.CAPACITY_EXCEEDED -> R.string.lifecycle_state_rejected
        handle.dropReason == ToastDropReason.EXPIRED -> R.string.lifecycle_state_expired
        handle.state == ToastHandle.State.PENDING -> R.string.lifecycle_state_waiting
        handle.state == ToastHandle.State.SHOWING -> R.string.lifecycle_state_showing
        handle.state == ToastHandle.State.CANCELED -> R.string.lifecycle_state_canceled
        handle.dismissReason == ToastHandle.DismissReason.REPLACED -> R.string.lifecycle_state_replaced
        else -> R.string.lifecycle_state_finished
    })
}

class LifecycleDemoFragment : Fragment() {
    val pageName: String get() = requireArguments().getString("page")!!
    lateinit var toastScope: KToastLifecycleScope
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        DemoPage.addStatus(body, getString(R.string.lifecycle_page_guide, pageName))
        return body
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toastScope = KToastLifecycleScope.create(requireActivity(), viewLifecycleOwner)
    }

    companion object {
        fun newInstance(page: String) = LifecycleDemoFragment().apply {
            arguments = Bundle().apply { putString("page", page) }
        }
    }
}
