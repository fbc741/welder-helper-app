package com.welder.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主界面
 * 权限管理 + 设置
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "WelderHelper"
        const val PERMISSION_REQUEST_CODE = 1001
        const val OVERLAY_REQUEST_CODE = 1002
    }

    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvQuestionCount: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnImport: Button
    private lateinit var btnClear: Button
    private lateinit var seekBarSize: SeekBar
    private lateinit var seekBarAlpha: SeekBar
    private lateinit var tvSize: TextView
    private lateinit var tvAlpha: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
        updateQuestionCount()
    }

    private fun initViews() {
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvQuestionCount = findViewById(R.id.tvQuestionCount)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnImport = findViewById(R.id.btnImport)
        btnClear = findViewById(R.id.btnClear)
        seekBarSize = findViewById(R.id.seekBarSize)
        seekBarAlpha = findViewById(R.id.seekBarAlpha)
        tvSize = findViewById(R.id.tvSize)
        tvAlpha = findViewById(R.id.tvAlpha)

        // 启动悬浮窗
        btnStart.setOnClickListener {
            Log.d(TAG, "启动按钮点击")
            if (checkAllPermissions()) {
                startFloatingService()
            } else {
                requestAllPermissions()
            }
        }

        // 停止悬浮窗
        btnStop.setOnClickListener {
            stopFloatingService()
        }

        // 导入题库
        btnImport.setOnClickListener {
            Toast.makeText(this, "题库已在APP内置", Toast.LENGTH_SHORT).show()
        }

        // 清空缓存
        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空题库")
                .setMessage("确定清空所有本地题库数据？")
                .setPositiveButton("确定") { _, _ ->
                    QuestionBank(this).clearAll()
                    updateQuestionCount()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 悬浮球大小调节
        seekBarSize.max = 100
        seekBarSize.progress = 56
        tvSize.text = "大小：56dp"
        seekBarSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = if (progress < 30) 30 else progress
                tvSize.text = "大小：${size}dp"
                FloatingService.instance?.updateSettings(size, seekBarAlpha.progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 透明度调节
        seekBarAlpha.max = 100
        seekBarAlpha.progress = 80
        tvAlpha.text = "透明度：80%"
        seekBarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress / 100f
                tvAlpha.text = "透明度：${progress}%"
                FloatingService.instance?.updateSettings(seekBarSize.progress, alpha)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ==================== 权限管理 ====================

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // 存储权限（Android 10以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }

        updatePermissionStatus()
    }

    private fun requestAllPermissions() {
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "请求悬浮窗权限")
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("请在设置中允许此APP显示在其他应用上层\n\n一加手机：设置 → 应用 → 特殊权限 → 悬浮窗")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_REQUEST_CODE)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
    }

    private fun checkAllPermissions(): Boolean {
        val canOverlay = Settings.canDrawOverlays(this)
        Log.d(TAG, "悬浮窗权限状态: $canOverlay")
        return canOverlay
    }

    private fun updatePermissionStatus() {
        val overlay = Settings.canDrawOverlays(this)
        tvPermissionStatus.text = if (overlay) {
            "✅ 悬浮窗权限已开启"
        } else {
            "❌ 悬浮窗权限未开启（点击启动会提示开启）"
        }
        Log.d(TAG, "权限状态更新: overlay=$overlay")
    }

    private fun updateQuestionCount() {
        val count = QuestionBank(this).getCount()
        tvQuestionCount.text = "本地题库：${count}题"
    }

    // ==================== 服务控制 ====================

    private fun startFloatingService() {
        Log.d(TAG, "启动悬浮窗服务")

        // 再次检查权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
            requestAllPermissions()
            return
        }

        try {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "悬浮窗已启动，请返回桌面查看", Toast.LENGTH_LONG).show()

            // 延迟关闭，让用户看到提示
            btnStart.postDelayed({
                finish()
            }, 1500)
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "悬浮窗已停止", Toast.LENGTH_SHORT).show()
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            // 从设置返回，检查权限
            updatePermissionStatus()
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "权限已开启，现在可以启动悬浮窗", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
