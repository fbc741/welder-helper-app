package com.welder.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*

/**
 * 悬浮窗服务 v3
 * - 不自动截屏，避免闪退
 * - 点击悬浮球时临时截屏+OCR
 * - 答案以简单Toast显示
 */
class SimpleFloatingService : Service() {

    companion object {
        const val TAG = "SimpleFloat"
        const val CHANNEL_ID = "welder_simple"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.welder.helper.START_SIMPLE"
        const val ACTION_STOP = "com.welder.helper.STOP_SIMPLE"
        const val ACTION_SCREENSHOT = "com.welder.helper.SCREENSHOT"
        const val REQUEST_CODE_SCREENSHOT = 2002

        var instance: SimpleFloatingService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingBall: View? = null
    private var answerView: View? = null

    // 屏幕参数
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 1

    // 截屏
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isCapturing = false

    // OCR
    private var textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // 题库
    private lateinit var questionBank: QuestionBank
    private lateinit var onlineSearcher: OnlineSearcher

    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        questionBank = QuestionBank(this)
        onlineSearcher = OnlineSearcher()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.d(TAG, "服务创建 - ${screenWidth}x${screenHeight}")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                handler.postDelayed({ showFloatingBall() }, 100)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        removeFloatingBall()
        removeAnswerView()
        releaseCapture()
        textRecognizer.close()
    }

    // ==================== 悬浮球 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBall != null) return
        if (!Settings.canDrawOverlays(this)) {
            toast("悬浮窗权限未开启")
            return
        }

        // 用代码创建，不依赖XML
        val size = dp2px(60)
        val ball = ImageView(this).apply {
            setImageResource(R.drawable.ic_float_ball)
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var dragged = false

        ball.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    startTouchX = e.rawX; startTouchY = e.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((e.rawX - startTouchX).let { it * it } + (e.rawY - startTouchY).let { it * it } > 100) dragged = true
                    params.x = startX + (e.rawX - startTouchX).toInt()
                    params.y = startY + (e.rawY - startTouchY).toInt()
                    try { windowManager.updateViewLayout(ball, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onBallClick()
                    else snapToEdge(params, ball)
                    true
                }
                else -> false
            }
        }

        floatingBall = ball
        try {
            windowManager.addView(ball, params)
            Log.d(TAG, "悬浮球已显示")
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮球失败", e)
        }
    }

    private fun snapToEdge(p: WindowManager.LayoutParams, v: View) {
        val target = if (p.x + dp2px(30) < screenWidth / 2) 0 else screenWidth - dp2px(60)
        serviceScope.launch {
            val from = p.x
            for (i in 1..8) {
                p.x = from + (target - from) * i / 8
                try { windowManager.updateViewLayout(v, p) } catch (_: Exception) { break }
                delay(16)
            }
        }
    }

    private fun removeFloatingBall() {
        floatingBall?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        floatingBall = null
    }

    // ==================== 点击：截屏+OCR+搜题 ====================

    private fun onBallClick() {
        Log.d(TAG, "点击悬浮球")
        toast("截屏中...")

        // 申请截屏权限（只需一次）
        if (mediaProjection == null) {
            requestScreenCapture()
        } else {
            doCapture()
        }
    }

    private fun requestScreenCapture() {
        val intent = Intent(this, ScreenCaptureActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // 从 ScreenCaptureActivity 调用
    fun onCapturePermission(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
            setupImageReader()
            doCapture()
        } else {
            toast("截屏权限被拒绝")
        }
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WelderCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun doCapture() {
        if (isCapturing) return
        isCapturing = true

        serviceScope.launch {
            delay(300)  // 等悬浮球收起
            val bitmap = captureScreen()
            if (bitmap == null) {
                toast("截屏失败")
                isCapturing = false
                return@launch
            }

            // OCR
            val text = performOCR(bitmap)
            bitmap.recycle()

            if (text.isNullOrBlank()) {
                toast("未识别到文字")
                isCapturing = false
                return@launch
            }

            Log.d(TAG, "OCR结果: ${text.take(50)}")

            // 搜题
            val answer = questionBank.findAnswer(text) ?: onlineSearcher.search(text)

            if (answer != null) {
                showAnswerOverlay(answer)
            } else {
                toast("未找到答案")
            }

            isCapturing = false
        }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val px = plane.pixelStride
            val row = plane.rowStride
            val pad = row - px * screenWidth
            val bmp = Bitmap.createBitmap(screenWidth + pad / px, screenHeight, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            // 裁剪到屏幕大小
            Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            Log.e(TAG, "截屏失败", e)
            null
        } finally {
            image.close()
        }
    }

    private suspend fun performOCR(bmp: Bitmap): String? {
        return suspendCancellableCoroutine { cont ->
            val img = InputImage.fromBitmap(bmp, 0)
            textRecognizer.process(img)
                .addOnSuccessListener { cont.resume(it.text) {} }
                .addOnFailureListener { cont.resume(null) {} }
        }
    }

    // ==================== 答案弹窗 ====================

    private fun showAnswerOverlay(answer: QuestionAnswer) {
        removeAnswerView()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0FFFFFF.toInt())
            setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
        }

        val tvTitle = TextView(this).apply {
            text = "📝 ${answer.question.take(40)}..."
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        }

        val tvAnswer = TextView(this).apply {
            text = "✅ 答案：${answer.answer}"
            textSize = 20f
            setTextColor(0xFFFF4444.toInt())
            setPadding(0, dp2px(8), 0, dp2px(4))
        }

        val tvExplain = TextView(this).apply {
            text = answer.explain ?: ""
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }

        val btnClose = Button(this).apply {
            text = "关闭"
            textSize = 12f
            setOnClickListener { removeAnswerView() }
        }

        container.addView(tvTitle)
        container.addView(tvAnswer)
        if (!answer.explain.isNullOrBlank()) container.addView(tvExplain)
        container.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        answerView = container
        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "显示答案失败", e)
        }

        // 8秒后自动消失
        handler.postDelayed({ removeAnswerView() }, 8000)
    }

    private fun removeAnswerView() {
        answerView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        answerView = null
    }

    // ==================== 辅助 ====================

    private fun releaseCapture() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "焊工助手", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("焊工助手运行中")
            .setContentText("点击悬浮球识题")
            .setSmallIcon(R.drawable.ic_float_ball)
            .build()
    }

    private fun dp2px(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
