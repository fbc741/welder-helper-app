package com.welder.helper

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明Activity，用于请求屏幕截取权限
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1001
        var resultCode: Int = Activity.RESULT_CANCELED
        var resultData: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            ScreenCaptureActivity.resultCode = resultCode
            ScreenCaptureActivity.resultData = data
            // 通知服务可以开始截屏
            FloatingService.onScreenCaptureReady?.invoke(resultCode, data)
        }
        finish()
    }
}
