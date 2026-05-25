package com.welder.helper

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 极简测试界面
 */
class TestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 直接用代码创建界面，不用XML
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val title = android.widget.TextView(this).apply {
            text = "悬浮球测试"
            textSize = 24f
            setPadding(0, 0, 0, 50)
        }
        layout.addView(title)

        val status = android.widget.TextView(this).apply {
            text = if (Settings.canDrawOverlays(this@TestActivity))
                "✅ 悬浮窗权限: 已开启" else "❌ 悬浮窗权限: 未开启"
            textSize = 16f
            setPadding(0, 0, 0, 30)
        }
        layout.addView(status)

        // 测试按钮
        val btnTest = Button(this).apply {
            text = "显示测试悬浮球"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@TestActivity)) {
                    Toast.makeText(this@TestActivity, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                    // 跳转到权限设置
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    return@setOnClickListener
                }

                // 启动极简悬浮球服务
                val serviceIntent = Intent(this@TestActivity, SimpleFloatingService::class.java).apply {
                    action = SimpleFloatingService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                Toast.makeText(this@TestActivity, "服务已启动，请返回桌面查看", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(btnTest)

        // 停止按钮
        val btnStop = Button(this).apply {
            text = "停止悬浮球"
            setOnClickListener {
                val serviceIntent = Intent(this@TestActivity, SimpleFloatingService::class.java).apply {
                    action = SimpleFloatingService.ACTION_STOP
                }
                startService(serviceIntent)
                Toast.makeText(this@TestActivity, "已停止", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnStop)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // 刷新权限状态
        recreate() // 简单粗暴，重新创建界面刷新状态
    }
}
