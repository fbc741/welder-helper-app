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
import android.widget.*
import kotlinx.coroutines.*

class SimpleFloatingService : Service() {

    companion object {
        private const val TAG = "SimpleFloat"
        private const val CHANNEL_ID = "welder_simple"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_SHOW = "com.welder.helper.SHOW"
        const val ACTION_HIDE = "com.welder.helper.HIDE"
    }

    private lateinit var windowManager: WindowManager
    private var floatingBall: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showBall()
            }
            ACTION_HIDE -> {
                hideBall()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        serviceScope.cancel()
        hideBall()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        if (floatingBall != null) {
            Log.d(TAG, "悬浮球已存在")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "没有悬浮窗权限")
            return
        }

        try {
            // 纯代码创建，不依赖XML
            val ball = ImageView(this).apply {
                setImageResource(R.drawable.ic_float_ball)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            val sizePx = (56 * resources.displayMetrics.density).toInt()

            val params = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 500
            }

            // 简单拖拽
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f

            ball.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(ball, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        Toast.makeText(this, "悬浮球已显示！功能开发中...", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(ball, params)
            floatingBall = ball
            Log.d(TAG, "悬浮球显示成功")

        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮球失败", e)
        }
    }

    private fun hideBall() {
        floatingBall?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除悬浮球失败", e)
            }
        }
        floatingBall = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "焊工助手", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("焊工助手运行中")
            .setSmallIcon(R.drawable.ic_float_ball)
            .build()
    }
}
