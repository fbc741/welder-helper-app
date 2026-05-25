package com.welder.helper

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 测试主界面
 */
class TestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tvPermissionStatus)
        val tvCount = findViewById<TextView>(R.id.tvQuestionCount)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        // 显示权限状态
        val overlay = Settings.canDrawOverlays(this)
        tvStatus.text = if (overlay) "✅ 悬浮窗权限已开启" else "❌ 悬浮窗权限未开启"

        // 显示题库数量
        tvCount.text = "本地题库：${QuestionBank(this).getCount()}题"

        btnStart.text = "启动悬浮球（点击识题）"

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SimpleFloatingService::class.java).apply {
                action = SimpleFloatingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(this, "悬浮球已启动！点击悬浮球可识题", Toast.LENGTH_LONG).show()
            finish()
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, SimpleFloatingService::class.java).apply {
                action = SimpleFloatingService.ACTION_STOP
            }
            startService(intent)
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvPermissionStatus)
        tvStatus.text = if (Settings.canDrawOverlays(this)) "✅ 悬浮窗权限已开启" else "❌ 悬浮窗权限未开启"
    }
}
