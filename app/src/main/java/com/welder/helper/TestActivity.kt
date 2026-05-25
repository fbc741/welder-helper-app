package com.welder.helper

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 极简测试界面 - 不自动触发截屏，只测试悬浮球
 */
class TestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TestActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        val tvStatus = findViewById<TextView>(R.id.tvTestStatus)
        val btnShow = findViewById<Button>(R.id.btnShowBall)
        val btnHide = findViewById<Button>(R.id.btnHideBall)

        updateStatus(tvStatus)

        btnShow.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先到设置中开启悬浮窗权限", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            try {
                val intent = Intent(this, SimpleFloatingService::class.java)
                intent.action = SimpleFloatingService.ACTION_SHOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "悬浮球已启动，请看屏幕左上角", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        btnHide.setOnClickListener {
            val intent = Intent(this, SimpleFloatingService::class.java)
            intent.action = SimpleFloatingService.ACTION_HIDE
            startService(intent)
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.tvTestStatus)?.let { updateStatus(it) }
    }

    private fun updateStatus(tv: TextView) {
        val hasPerm = Settings.canDrawOverlays(this)
        tv.text = if (hasPerm) "✅ 悬浮窗权限已开启\n\n点「显示悬浮球」然后看屏幕左上角"
                  else "❌ 悬浮窗权限未开启\n请到设置中开启"
    }
}
