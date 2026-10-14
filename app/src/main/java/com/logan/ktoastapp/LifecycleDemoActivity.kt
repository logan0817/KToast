package com.logan.ktoastapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.logan.ktoast.lifecycle.KToastLifecycleScope
import com.logan.ktoast.runtime.KToastBehavior
import com.logan.ktoast.runtime.KToastCallbacks
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoast.runtime.ToastHandle

/** 两页共用 Activity，提示绑定各自 View Owner。测试也使用这条真实路径。 */
class LifecycleDemoActivity : AppCompatActivity() {
    companion object { private const val CONTAINER_ID = 0x00a12001 }
    private lateinit var status: TextView
    val page: LifecycleDemoFragment
        get() = supportFragmentManager.findFragmentById(CONTAINER_ID) as LifecycleDemoFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = 24 + bars.left, top = 16 + bars.top,
                right = 24 + bars.right, bottom = 16 + bars.bottom)
            insets
        }
        status = TextView(this).apply {
            text = getString(R.string.lifecycle_policy_summary)
            textSize = 16f
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = getString(R.string.lifecycle_switch)
            setOnClickListener { switchPage(if (page.pageName == "A") "B" else "A") }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.lifecycle_rebuild)
            setOnClickListener { rebuildPageView() }
        })
        root.addView(FrameLayout(this).apply { id = CONTAINER_ID },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) switchPage("A")
    }

    fun switchPage(name: String) {
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER_ID, LifecycleDemoFragment.newInstance(name)).commitNow()
    }

    fun rebuildPageView() {
        val current = page
        supportFragmentManager.beginTransaction().detach(current).commitNow()
        supportFragmentManager.beginTransaction().attach(current).commitNow()
    }

    fun report(handle: ToastHandle) {
        status.text = getString(R.string.lifecycle_result, handle.state, handle.dismissReason, handle.dropReason ?: getString(R.string.lifecycle_no_drop))
    }
}

class LifecycleDemoFragment : Fragment() {
    val pageName: String get() = requireArguments().getString("page")!!
    lateinit var toastScope: KToastLifecycleScope
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = getString(R.string.lifecycle_page_guide, pageName)
                textSize = 18f
            })
        }
        fun button(label: String, action: () -> Unit) {
            body.addView(Button(requireContext()).apply { text = label; setOnClickListener { action() } })
        }
        button(getString(R.string.lifecycle_show)) { toastScope.show("页面 $pageName 新提示", callbacks = callbacks()) }
        button(getString(R.string.lifecycle_delayed)) {
            toastScope.showDelayed("页面 $pageName 延时提示", 3_000L, callbacks = callbacks())
        }
        button(getString(R.string.lifecycle_capacity)) {
            toastScope.cancel()
            repeat(40) { toastScope.showDelayed("容量示例 $it", 60_000L, callbacks = callbacks()) }
        }
        button(getString(R.string.lifecycle_expiry)) {
            toastScope.cancel()
            repeat(10) {
                toastScope.show("等待示例 $it", Toast.LENGTH_LONG,
                    KToastBehavior(KToastDisplayMode.QUEUE), callbacks())
            }
        }
        button(getString(R.string.lifecycle_cancel)) { toastScope.cancel() }
        return ScrollView(requireContext()).apply { addView(body) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toastScope = KToastLifecycleScope.create(requireActivity(), viewLifecycleOwner)
    }

    private fun callbacks() = KToastCallbacks(onDismiss = { handle, _ ->
        (activity as? LifecycleDemoActivity)?.report(handle)
    })

    companion object {
        fun newInstance(page: String) = LifecycleDemoFragment().apply {
            arguments = Bundle().apply { putString("page", page) }
        }
    }
}
