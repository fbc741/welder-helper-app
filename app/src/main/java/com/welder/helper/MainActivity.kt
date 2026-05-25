package com.welder.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
        const val PERMISSION_REQUEST_CODE = 1001
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
            // TODO: 实现题库导入功能（从文件或网络）
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
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("请在设置中允许此APP显示在其他应用上层")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 检查屏幕截取权限（启动时会自动请求）
        updatePermissionStatus()
    }

    private fun checkAllPermissions(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updatePermissionStatus() {
        val overlay = Settings.canDrawOverlays(this)
        tvPermissionStatus.text = if (overlay) {
            "✅ 悬浮窗权限已开启"
        } else {
            "❌ 悬浮窗权限未开启"
        }
    }

    private fun updateQuestionCount() {
        val count = QuestionBank(this).getCount()
        tvQuestionCount.text = "本地题库：${count}题"
    }

    // ==================== 服务控制 ====================

    private fun startFloatingService() {
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
        finish()  // 关闭主界面，让APP在后台运行
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
}
