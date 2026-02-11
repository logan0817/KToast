package com.logan.ktoastapp

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.logan.ktoast.KToast
import com.logan.ktoast.debugShow
import com.logan.ktoast.toast
import com.logan.ktoast.toastDelayed
import com.logan.ktoastapp.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    companion object {
        val TAG = "MainActivityTAG"
    }

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    // 模拟延时任务的句柄，用于测试取消
    private var delayedTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        setupInsets()
        setListeners()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = insets.top)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = MainActivity::class.java.simpleName
    }

    private fun setListeners() {
        // 基础文本
        binding.btnBasic.setOnClickListener {
            "Hello KToast! 这是一个普通消息".toast()
        }

        // 长时间显示
        binding.btnLong.setOnClickListener {
            "这条消息会显示久一点 (Long)".toast(duration = Toast.LENGTH_LONG)
        }

        // 修改颜色 (DSL)
        binding.btnColor.setOnClickListener {
            "自定义配色：蓝底白字".toast {
                backgroundColor = Color.parseColor("#2196F3")
                textColor = Color.WHITE
                backgroundRadius = 50f // 胶囊形状
            }
        }

        // 带图标
        binding.btnIcon.setOnClickListener {
            "带图标的提示".toast {
                // 这里使用系统图标演示，实际可用 R.drawable.xxx
                icon = R.drawable.ktoast_ic_warning
                iconColor = Color.YELLOW
                iconSize = 32f
            }
        }

        // 方形 + 边距
        binding.btnShape.setOnClickListener {
            "方形直角 + 大内边距".toast {
                backgroundRadius = 0f
                paddingHorizontal = 40
                paddingVertical = 20
                backgroundColor = Color.DKGRAY
            }
        }

        // 顶部显示
        binding.btnGravityTop.setOnClickListener {
            "顶部显示 + 偏移".toast {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                yOffset = 150 // 距离顶部 150px
            }
        }

        // 居中显示
        binding.btnGravityCenter.setOnClickListener {
            "屏幕正中间".toast {
                gravity = Gravity.CENTER
            }
        }

        // 侧边偏移 (xOffset 测试)
        binding.btnOffset.setOnClickListener {
            "靠左偏移 100px".toast {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                xOffset = 100
                backgroundColor = Color.parseColor("#673AB7")
            }
        }

        // 慢动作动画
        binding.btnAnimSlow.setOnClickListener {
            "慢动作进场与消失".toast {
                animationDuration = 800L
            }
        }

        // 可点击消失
        binding.btnInteractive.setOnClickListener {
            "点我立即消失！".toast(duration = Toast.LENGTH_LONG) {
                cancelOnTouch = true
                backgroundColor = Color.MAGENTA
            }
        }

        // 子线程调用 (测试 runOnMainThread)
        binding.btnThread.setOnClickListener {
            thread {
                Thread.sleep(500)
                "我是从子线程发出的消息".toast()
            }
        }

        // 延时显示
        binding.btnDelayed.setOnClickListener {
            "延时 2秒 成功弹出".toastDelayed(2000L)
        }

        // 测试撤回延时任务
        binding.btnDelayedCancel.setOnClickListener {
            "这条消息不应该出现".toast() // 先弹一个提示

            // 发起一个3秒后的任务
            delayedTask = "如果你看到了这条，说明撤回失败".toastDelayed(3000L)

            // 1秒后模拟取消
            Handler(Looper.getMainLooper()).postDelayed({
                KToast.cancelDelayed(delayedTask)
                "延时任务已成功撤回".toast()
            }, 1000)
        }

        // Debug 模式
        binding.switchDebug.setOnCheckedChangeListener { _, isChecked ->
            KToast.debugMode = isChecked
        }

        binding.btnDebugShow.setOnClickListener {
            "这是一条 Debug 消息，只有开关打开才能看到".debugShow()
        }

        // Java 兼容性测试
        binding.btnJava.setOnClickListener {
            // 模拟 Java 静态调用
            KToast.show("Java 静态方法调用成功", Toast.LENGTH_SHORT)
        }

        // App 层自定义扩展 (模拟业务封装)
        binding.btnAppExtSuccess.setOnClickListener {
            "操作成功！".toastSuccess()
        }
        // App 层自定义扩展 (模拟业务封装)
        binding.btnAppExtError.setOnClickListener {
            "保存失败！".toastError()
        }
        // App 层自定义扩展 (模拟业务封装)
        binding.btnAppExtWarning.setOnClickListener {
            "请检查网络！".toastWarning()
        }
        // 全部取消
        binding.btnCancelAll.setOnClickListener {
            KToast.cancel()
        }
    }
}
