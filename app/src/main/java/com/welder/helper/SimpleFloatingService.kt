package com.welder.helper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 极简悬浮球测试
 */
class SimpleFloatingService : Service() {

    companion object {
        const val TAG = "SimpleFloat"
        const val CHANNEL_ID = "simple_float"
        const val NOTIFICATION_ID = 9999
        const val ACTION_START = "com.welder.helper.START_SIMPLE"
        const val ACTION_STOP = "com.welder.helper.STOP_SIMPLE"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
                showSimpleBall()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        removeBall()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSimpleBall() {
        if (floatingView != null) return

        Log.d(TAG, "显示悬浮球")

        // 检查权限
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "没有悬浮窗权限!")
            Toast.makeText(this, "错误：没有悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        try {
            // 创建一个简单的红色圆形
            floatingView = View(this).apply {
                setBackgroundColor(0xFFFF0000.toInt()) // 红色
            }

            val params = WindowManager.LayoutParams(
                150, 150,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            windowManager.addView(floatingView, params)
            Log.d(TAG, "悬浮球添加成功!")
            Toast.makeText(this, "悬浮球已显示!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮球失败", e)
            Toast.makeText(this, "添加悬浮球失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeBall() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除失败", e)
            }
        }
        floatingView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "测试悬浮球",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("测试悬浮球")
            .setContentText("运行中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
