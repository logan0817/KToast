package com.logan.ktoastapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.logan.ktoast.KToast
import com.logan.ktoast.debugShow
import com.logan.ktoast.toast
import com.logan.ktoast.toastHandle
import com.logan.ktoast.toastDelayed
import com.logan.ktoast.runtime.KToastContentFactory
import com.logan.ktoast.runtime.KToastDisplayMode
import com.logan.ktoastapp.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    companion object {
        val TAG = "MainActivityTAG"
        private const val REQUEST_CODE_NOTIFICATIONS = 101
        private const val BACKGROUND_DEMO_DELAY_MS = 2000L
    }

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val demoHandler = Handler(Looper.getMainLooper())

    // 模拟延时任务的句柄，用于测试取消
    private var delayedTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        binding.switchDebug.isChecked = KToast.debugMode
        setupInsets()
        setListeners()
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.demo_toolbar_title)
    }

    private fun setListeners() {
        binding.btnBasic.setOnClickListener {
            getString(R.string.demo_toast_basic).toast()
            recordAction(R.string.demo_basic_toast, R.string.demo_guide_try_style)
        }

        binding.btnLong.setOnClickListener {
            getString(R.string.demo_toast_long).toast(duration = Toast.LENGTH_LONG)
            recordAction(R.string.demo_long_toast, R.string.demo_guide_try_style)
        }

        binding.btnColor.setOnClickListener {
            getString(R.string.demo_toast_color).toast {
                backgroundColor = Color.parseColor("#2196F3")
                textColor = Color.WHITE
                backgroundRadius = 50f
            }
            recordAction(R.string.demo_change_color, R.string.demo_guide_try_behavior)
        }

        binding.btnIcon.setOnClickListener {
            getString(R.string.demo_toast_icon).toast {
                icon = R.drawable.ktoast_ic_warning
                iconColor = Color.YELLOW
                iconSize = 32f
            }
            recordAction(R.string.demo_icon_toast, R.string.demo_guide_try_behavior)
        }

        binding.btnShape.setOnClickListener {
            getString(R.string.demo_toast_shape).toast {
                backgroundRadius = 0f
                paddingHorizontal = 40
                paddingVertical = 20
                backgroundColor = Color.DKGRAY
            }
            recordAction(R.string.demo_shape_toast, R.string.demo_guide_try_behavior)
        }

        binding.btnGravityTop.setOnClickListener {
            getString(R.string.demo_toast_top).toast {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                yOffset = 150
            }
            recordAction(R.string.demo_top_toast, R.string.demo_guide_try_behavior)
        }

        binding.btnGravityCenter.setOnClickListener {
            getString(R.string.demo_toast_center).toast {
                gravity = Gravity.CENTER
            }
            recordAction(R.string.demo_center_toast, R.string.demo_guide_try_behavior)
        }

        binding.btnOffset.setOnClickListener {
            getString(R.string.demo_toast_offset).toast {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                xOffset = 100
                backgroundColor = Color.parseColor("#673AB7")
            }
            recordAction(R.string.demo_offset_toast, R.string.demo_guide_try_behavior)
        }

        binding.btnAnimSlow.setOnClickListener {
            getString(R.string.demo_toast_slow_animation).toast {
                animationDuration = 800L
            }
            recordAction(R.string.demo_slow_animation, R.string.demo_guide_try_system)
        }

        binding.btnInteractive.setOnClickListener {
            getString(R.string.demo_toast_interactive).toast(duration = Toast.LENGTH_LONG) {
                cancelOnTouch = true
                backgroundColor = Color.MAGENTA
            }
            recordAction(R.string.demo_interactive_toast, R.string.demo_guide_try_system)
        }

        binding.btnThread.setOnClickListener {
            thread {
                Thread.sleep(500)
                getString(R.string.demo_toast_thread).toast()
            }
            recordAction(R.string.demo_thread_toast, R.string.demo_guide_try_system)
        }

        binding.btnQueueMode.setOnClickListener {
            listOf(
                getString(R.string.demo_toast_queue_1),
                getString(R.string.demo_toast_queue_2),
                getString(R.string.demo_toast_queue_3)
            ).forEach { message ->
                message.toastHandle(
                    duration = Toast.LENGTH_SHORT,
                    behavior = {
                        displayMode = KToastDisplayMode.QUEUE
                        groupKey = "queue-demo"
                    }
                )
            }
            recordAction(R.string.demo_queue_mode, R.string.demo_guide_try_system)
        }

        binding.btnIgnoreDuplicate.setOnClickListener {
            repeat(3) {
                getString(R.string.demo_toast_ignore_duplicate).toastHandle(
                    behavior = {
                        displayMode = KToastDisplayMode.IGNORE_IF_SAME
                        windowMillis = 1_500L
                    }
                )
            }
            recordAction(R.string.demo_ignore_duplicate, R.string.demo_guide_try_system)
        }

        binding.btnCustomFactory.setOnClickListener {
            showCodeContentFactoryToast()
            recordAction(R.string.demo_custom_factory_code, R.string.demo_guide_try_behavior)
        }

        binding.btnCustomFactoryXml.setOnClickListener {
            showXmlContentFactoryToast()
            recordAction(R.string.demo_custom_factory_xml, R.string.demo_guide_try_behavior)
        }

        binding.btnCancelGroup.setOnClickListener {
            listOf(
                getString(R.string.demo_toast_upload_queue_1),
                getString(R.string.demo_toast_upload_queue_2),
                getString(R.string.demo_toast_upload_queue_3)
            ).forEach { message ->
                message.toastHandle(
                    duration = Toast.LENGTH_LONG,
                    behavior = {
                        displayMode = KToastDisplayMode.QUEUE
                        groupKey = "upload-demo"
                    }
                )
            }
            demoHandler.postDelayed({
                KToast.cancelGroup("upload-demo")
                getString(R.string.demo_toast_cancel_group_result).toast()
                recordAction(R.string.demo_cancel_group, R.string.demo_guide_try_system)
            }, 1_200L)
        }

        binding.btnRequestPermission.setOnClickListener {
            if (areNotificationsEnabled()) {
                getString(R.string.demo_toast_permission_on).toast()
                recordAction(R.string.demo_action_permission_checked, R.string.demo_background_toast)
            } else {
                requestNotificationPermission(this)
                recordAction(R.string.demo_action_permission_checked, R.string.demo_guide_try_system)
            }
        }
        binding.btnBackgrounStatus.setOnClickListener {
            if (areNotificationsEnabled()) {
                getString(R.string.demo_toast_background_warning).toastWarning()
                demoHandler.postDelayed({
                    moveTaskToBack(true)
                    demoHandler.postDelayed({
                        getString(R.string.demo_toast_background_system).toast()
                    }, BACKGROUND_DEMO_DELAY_MS)
                }, BACKGROUND_DEMO_DELAY_MS)
                recordAction(R.string.demo_action_background_demo, R.string.demo_guide_try_wrapper)
            } else {
                getString(R.string.demo_toast_background_permission_off).toastError()
                recordAction(R.string.demo_action_permission_checked, R.string.demo_guide_try_system)
            }
        }

        binding.btnDelayed.setOnClickListener {
            getString(R.string.demo_toast_delayed).toastDelayed(2000L)
            recordAction(R.string.demo_delayed_toast, R.string.demo_cancel_group)
        }

        binding.btnDelayedCancel.setOnClickListener {
            delayedTask = getString(R.string.demo_toast_delayed_cancel_pending).toastDelayed(3000L)

            demoHandler.postDelayed({
                KToast.cancelDelayed(delayedTask)
                delayedTask = null
                getString(R.string.demo_toast_delayed_cancel_success).toast()
                recordAction(R.string.demo_delayed_cancel, R.string.demo_guide_try_system)
            }, 1000)
        }

        binding.switchDebug.setOnCheckedChangeListener { _, isChecked ->
            KToast.debugMode = isChecked
            recordAction(
                if (isChecked) R.string.demo_action_debug_toggle_on else R.string.demo_action_debug_toggle_off,
                if (areNotificationsEnabled()) R.string.demo_guide_try_wrapper else R.string.demo_guide_try_system
            )
        }

        binding.btnDebugShow.setOnClickListener {
            getString(R.string.demo_toast_debug).debugShow()
            recordAction(R.string.demo_debug_show, R.string.demo_guide_try_wrapper)
        }

        binding.btnJava.setOnClickListener {
            KToast.show(getString(R.string.demo_toast_java), Toast.LENGTH_SHORT)
            recordAction(R.string.demo_java_usage, R.string.demo_guide_try_wrapper)
        }

        binding.btnAppExtSuccess.setOnClickListener {
            getString(R.string.demo_toast_app_success).toastSuccess()
            recordAction(R.string.demo_app_success, R.string.demo_guide_try_wrapper)
        }
        binding.btnAppExtError.setOnClickListener {
            getString(R.string.demo_toast_app_error).toastError()
            recordAction(R.string.demo_app_error, R.string.demo_guide_try_wrapper)
        }
        binding.btnAppExtWarning.setOnClickListener {
            getString(R.string.demo_toast_app_warning).toastWarning()
            recordAction(R.string.demo_app_warning, R.string.demo_guide_try_wrapper)
        }
        binding.btnCancelAll.setOnClickListener {
            KToast.cancel()
            delayedTask = null
            demoHandler.removeCallbacksAndMessages(null)
            recordAction(R.string.demo_action_cancel_all, R.string.demo_guide_try_smoke)
        }
    }

    override fun onDestroy() {
        demoHandler.removeCallbacksAndMessages(null)
        KToast.cancelDelayed(delayedTask)
        delayedTask = null
        _binding = null
        super.onDestroy()
    }

    private fun showCodeContentFactoryToast() {
        getString(R.string.demo_toast_custom_factory_code).toastHandle {
            borderColor = Color.WHITE
            borderWidth = 1
            minWidth = 220
            minHeight = 64
            maxWidthRatio = 0.9f
            contentDescription = getString(R.string.demo_toast_custom_factory_code_desc)
            contentFactory = KToastContentFactory { context, message, config ->
                val density = context.resources.displayMetrics.density
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(config.backgroundColor)
                        cornerRadius = 20f * density
                        setStroke((config.borderWidth * density).toInt(), config.borderColor ?: Color.WHITE)
                    }
                    setPadding(
                        (20 * density).toInt(),
                        (14 * density).toInt(),
                        (20 * density).toInt(),
                        (14 * density).toInt()
                    )
                    addView(TextView(context).apply {
                        text = context.getString(R.string.demo_toast_custom_factory_code_title)
                        textSize = 12f
                        setTextColor(Color.parseColor("#FFE082"))
                    })
                    addView(TextView(context).apply {
                        text = message
                        textSize = config.textSize
                        setTextColor(config.textColor)
                    })
                }
            }
        }
    }

    private fun showXmlContentFactoryToast() {
        getString(R.string.demo_toast_custom_factory_xml).toastHandle {
            borderColor = Color.WHITE
            borderWidth = 1
            minWidth = 220
            minHeight = 64
            maxWidthRatio = 0.9f
            contentDescription = getString(R.string.demo_toast_custom_factory_xml_desc)
            contentFactory = KToastContentFactory { context, message, config ->
                LayoutInflater.from(context)
                    .inflate(R.layout.view_demo_toast_content, null, false)
                    .apply {
                        backgroundTintList = ColorStateList.valueOf(config.backgroundColor)
                        findViewById<TextView>(R.id.tvToastTitle).apply {
                            text = context.getString(R.string.demo_toast_custom_factory_xml_title)
                        }
                        findViewById<TextView>(R.id.tvToastMessage).apply {
                            text = message
                            textSize = config.textSize
                            setTextColor(config.textColor)
                        }
                    }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATIONS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getString(R.string.demo_toast_permission_granted).toast()
                recordAction(R.string.demo_action_permission_checked, R.string.demo_background_toast)
            } else {
                getString(R.string.demo_toast_permission_denied).toast()
                recordAction(R.string.demo_action_permission_checked, R.string.demo_guide_try_system)
            }
        }
    }

    /**
     * 检查通知权限是否开启
     */
    private fun areNotificationsEnabled(): Boolean {
        val manager = NotificationManagerCompat.from(this)
        return manager.areNotificationsEnabled()
    }

    /**
     * 申请通知权限
     * @param activity 上下文
     * @param requestCode 请求码
     */
    private fun requestNotificationPermission(
        activity: Activity,
        requestCode: Int = REQUEST_CODE_NOTIFICATIONS
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 运行时权限申请
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    requestCode
                )
            }
        } else {
            // Android 13 以下，如果权限被关了，只能引导用户去设置页开启
            if (!areNotificationsEnabled()) {
                openNotificationSettings(activity)
            }
        }
    }

    /**
     * 引导用户跳转到系统设置里的通知开关页面
     */
    private fun openNotificationSettings(context: Context) {
        val intent = Intent().apply {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> {
                    action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    putExtra("app_package", context.packageName)
                    putExtra("app_uid", context.applicationInfo.uid)
                }
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun refreshDashboard() {
        val permissionEnabled = areNotificationsEnabled()
        val debugEnabled = KToast.debugMode
        renderDashboard(
            permissionEnabled = permissionEnabled,
            debugEnabled = debugEnabled,
            lastActionRes = R.string.demo_status_idle,
            guideRes = if (permissionEnabled) R.string.demo_status_guide_default else R.string.demo_guide_try_system
        )
    }

    private fun recordAction(actionRes: Int, guideRes: Int) {
        renderDashboard(
            permissionEnabled = areNotificationsEnabled(),
            debugEnabled = KToast.debugMode,
            lastActionRes = actionRes,
            guideRes = guideRes
        )
    }

    private fun renderDashboard(
        permissionEnabled: Boolean,
        debugEnabled: Boolean,
        lastActionRes: Int,
        guideRes: Int
    ) {
        binding.tvStatusPermission.text = getString(
            R.string.demo_status_permission_template,
            getString(if (permissionEnabled) R.string.demo_status_permission_on else R.string.demo_status_permission_off)
        )
        binding.tvStatusDebug.text = getString(
            R.string.demo_status_debug_template,
            getString(if (debugEnabled) R.string.demo_status_debug_on else R.string.demo_status_debug_off)
        )
        binding.tvLastAction.text = getString(
            R.string.demo_status_action_template,
            getString(lastActionRes)
        )
        binding.tvStatusGuide.text = getString(
            R.string.demo_status_guide_template,
            getString(guideRes)
        )
        tintStatusChip(
            binding.tvStatusPermission,
            if (permissionEnabled) R.color.demo_success else R.color.demo_warning
        )
        tintStatusChip(
            binding.tvStatusDebug,
            if (debugEnabled) R.color.demo_primary_action else R.color.demo_status_neutral
        )
    }

    private fun tintStatusChip(view: TextView, colorRes: Int) {
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }
}
