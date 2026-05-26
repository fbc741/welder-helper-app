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
import android.graphics.Color
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
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.*

/**
 * 悬浮窗服务 v4
 * 改进：
 * - 点击悬浮球截屏+OCR识题
 * - OCR失败后可手动输入题目
 * - 答案弹窗显示更清晰
 * - 本地题库200题 + 联网搜题三引擎
 */
class SimpleFloatingService : Service() {

    companion object {
        const val TAG = "SimpleFloat"
        const val CHANNEL_ID = "welder_simple"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.welder.helper.START_SIMPLE"
        const val ACTION_STOP = "com.welder.helper.STOP_SIMPLE"

        var instance: SimpleFloatingService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingBall: View? = null
    private var answerView: View? = null

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
                    val dx = e.rawX - startTouchX
                    val dy = e.rawY - startTouchY
                    if (dx * dx + dy * dy > 100) dragged = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
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

        // 先尝试截屏OCR
        if (mediaProjection != null) {
            doCapture()
        } else {
            // 没有截屏权限，提示用户
            toast("请先允许截屏权限")
            requestScreenCapture()
        }
    }

    private fun doCapture() {
        if (isCapturing) return
        isCapturing = true

        serviceScope.launch {
            delay(300)
            val bitmap = captureScreen()
            if (bitmap == null) {
                toast("截屏失败")
                isCapturing = false
                return@launch
            }

            val text = performOCR(bitmap)
            bitmap.recycle()

            if (text.isNullOrBlank()) {
                showManualInputDialog()
                isCapturing = false
                return@launch
            }

            Log.d(TAG, "OCR结果: ${text.take(80)}")

            // 搜题
            val answer = questionBank.findAnswer(text) ?: onlineSearcher.search(text)

            if (answer != null) {
                showAnswerOverlay(answer)
            } else {
                // 搜不到，显示手动输入对话框
                showManualInputDialog(text)
            }

            isCapturing = false
        }
    }

    // ==================== 手动输入题目 ====================

    private fun showManualInputDialog(ocrText: String? = null) {
        val input = EditText(this).apply {
            hint = "请输入题目关键词..."
            setText(ocrText?.take(50))
            setSelection(text.length)
            textSize = 16f
            minLines = 2
            maxLines = 4
        }

        // 用Activity来显示对话框（Service中不能直接弹Dialog）
        serviceScope.launch(Dispatchers.Main) {
            try {
                val dialog = AlertDialog.Builder(this@SimpleFloatingService)
                    .setTitle("📝 手动搜题")
                    .setMessage("OCR未识别到题目或未找到答案，请输入题目关键词搜索")
                    .setView(input)
                    .setPositiveButton("搜索") { _, _ ->
                        val searchText = input.text.toString().trim()
                        if (searchText.isNotEmpty()) {
                            searchAndShow(searchText)
                        } else {
                            toast("请输入题目")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .create()

                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()

                // 10秒后自动关闭
                handler.postDelayed({ dialog.dismiss() }, 10000)
            } catch (e: Exception) {
                Log.e(TAG, "显示手动输入对话框失败", e)
                toast("未找到答案")
            }
        }
    }

    private fun searchAndShow(searchText: String) {
        toast("搜索中...")
        serviceScope.launch {
            val answer = withContext(Dispatchers.IO) {
                questionBank.findAnswer(searchText) ?: onlineSearcher.search(searchText)
            }

            if (answer != null) {
                showAnswerOverlay(answer)
            } else {
                toast("未找到答案，请尝试其他关键词")
            }
        }
    }

    // ==================== 截屏 ====================

    private fun requestScreenCapture() {
        val intent = Intent(this, ScreenCaptureActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun onCapturePermission(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
            setupImageReader()
            toast("截屏权限已获取，点击悬浮球识题")
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
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR失败", e)
                    cont.resume(null) {}
                }
        }
    }

    // ==================== 答案弹窗 ====================

    private fun showAnswerOverlay(answer: QuestionAnswer) {
        removeAnswerView()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0FFFFFF.toInt())
            setPadding(dp2px(20), dp2px(16), dp2px(20), dp2px(16))
        }

        // 题目（截断显示）
        val displayQuestion = if (answer.question.length > 50) {
            answer.question.substring(0, 50) + "..."
        } else {
            answer.question
        }

        val tvTitle = TextView(this).apply {
            text = "📝 $displayQuestion"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        }

        // 答案（大字醒目）
        val tvAnswer = TextView(this).apply {
            text = "✅ 答案：${answer.answer}"
            textSize = 24f
            setTextColor(0xFFFF4444.toInt())
            setPadding(0, dp2px(12), 0, dp2px(8))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // 解析
        val tvExplain = TextView(this).apply {
            text = answer.explain ?: ""
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp2px(4), 0, dp2px(8))
        }

        // 关闭按钮
        val btnClose = Button(this).apply {
            text = "关闭"
            textSize = 14f
            setOnClickListener { removeAnswerView() }
        }

        container.addView(tvTitle)
        container.addView(tvAnswer)
        if (!answer.explain.isNullOrBlank()) container.addView(tvExplain)
        container.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT.dp2pxMinusPadding(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
    private fun Int.dp2pxMinusPadding() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}