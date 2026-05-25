package com.welder.helper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 * 负责：悬浮球管理、屏幕截取、OCR识别、答案弹窗
 */
class FloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "welder_floating"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.welder.helper.START"
        const val ACTION_STOP = "com.welder.helper.STOP"

        var instance: FloatingService? = null
            private set

        var onScreenCaptureReady: ((Int, Intent?) -> Unit)? = null
    }

    // 悬浮球窗口
    private lateinit var windowManager: WindowManager
    private var floatingBall: View? = null
    private var answerPopup: View? = null

    // 屏幕截取
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 1

    // OCR
    private val textRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 题库
    private lateinit var questionBank: QuestionBank
    private lateinit var onlineSearcher: OnlineSearcher

    // 设置
    private var ballSize = 56  // dp
    private var ballAlpha = 0.8f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        questionBank = QuestionBank(this)
        onlineSearcher = OnlineSearcher()

        // 获取屏幕尺寸
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                initScreenCapture()
                showFloatingBall()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        removeFloatingBall()
        removeAnswerPopup()
        stopScreenCapture()
        textRecognizer.close()
    }

    // ==================== 屏幕截取 ====================

    @SuppressLint("WrongConstant")
    private fun initScreenCapture() {
        // 请求截屏权限
        val intent = Intent(this, ScreenCaptureActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        onScreenCaptureReady = { resultCode, data ->
            if (resultCode == RESULT_OK && data != null) {
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                setupImageReader()
            }
        }
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        } finally {
            image.close()
        }
    }

    private fun stopScreenCapture() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    // ==================== 悬浮球 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBall != null) return

        val inflater = LayoutInflater.from(this)
        floatingBall = inflater.inflate(R.layout.floating_ball, null)

        val params = WindowManager.LayoutParams(
            dp2px(ballSize),
            dp2px(ballSize),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }

        // 拖拽逻辑
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingBall?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) {
                        isDragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingBall, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onFloatingBallClick()
                    } else {
                        // 边缘吸附
                        snapToEdge(params)
                    }
                    true
                }
                else -> false
            }
        }

        floatingBall?.alpha = ballAlpha
        windowManager.addView(floatingBall, params)
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val centerX = params.x + dp2px(ballSize) / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - dp2px(ballSize)

        serviceScope.launch {
            val startX = params.x
            val steps = 10
            for (i in 1..steps) {
                params.x = startX + (targetX - startX) * i / steps
                try {
                    windowManager.updateViewLayout(floatingBall, params)
                } catch (e: Exception) {
                    break
                }
                delay(16)
            }
        }
    }

    private fun removeFloatingBall() {
        floatingBall?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        floatingBall = null
    }

    // ==================== 识题逻辑 ====================

    private fun onFloatingBallClick() {
        serviceScope.launch {
            // 1. 截屏
            val bitmap = withContext(Dispatchers.IO) { captureScreen() }
            if (bitmap == null) {
                showToast("截屏失败，请检查权限")
                return@launch
            }

            // 2. OCR识别
            val text = withContext(Dispatchers.IO) { performOCR(bitmap) }
            if (text.isNullOrBlank()) {
                showToast("未识别到文字")
                bitmap.recycle()
                return@launch
            }

            // 3. 本地题库匹配
            val localResult = withContext(Dispatchers.IO) {
                questionBank.findAnswer(text)
            }

            if (localResult != null) {
                showAnswerPopup(localResult)
            } else {
                // 4. 联网搜题
                val onlineResult = withContext(Dispatchers.IO) {
                    onlineSearcher.search(text)
                }
                if (onlineResult != null) {
                    showAnswerPopup(onlineResult)
                } else {
                    showToast("未找到答案")
                }
            }

            bitmap.recycle()
        }
    }

    private suspend fun performOCR(bitmap: Bitmap): String? {
        return suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.text) {}
                }
                .addOnFailureListener { e ->
                    continuation.resume(null) {}
                }
        }
    }

    // ==================== 答案弹窗 ====================

    private fun showAnswerPopup(answer: QuestionAnswer) {
        removeAnswerPopup()

        val inflater = LayoutInflater.from(this)
        answerPopup = inflater.inflate(R.layout.answer_popup, null)

        // 设置内容
        answerPopup?.findViewById<TextView>(R.id.tvQuestion)?.text = answer.question
        answerPopup?.findViewById<TextView>(R.id.tvAnswer)?.text = "答案：${answer.answer}"
        answerPopup?.findViewById<TextView>(R.id.tvExplain)?.text = answer.explain ?: ""

        // 关闭按钮
        answerPopup?.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            removeAnswerPopup()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(answerPopup, params)

        // 5秒后自动消失
        serviceScope.launch {
            delay(5000)
            removeAnswerPopup()
        }
    }

    private fun removeAnswerPopup() {
        answerPopup?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        answerPopup = null
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "焊工答题助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮窗服务通知"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("焊工答题助手")
            .setContentText("悬浮窗运行中...")
            .setSmallIcon(R.drawable.ic_float_ball)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== 工具方法 ====================

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun showToast(msg: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(this@FloatingService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 公开方法 ====================

    fun updateSettings(size: Int, alpha: Float) {
        ballSize = size
        ballAlpha = alpha
        floatingBall?.alpha = alpha
    }
}
