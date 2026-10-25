package com.yuyan.imemodule.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.yuyan.imemodule.R

/**
 * 输入法服务无法直接弹出权限对话框，借这个透明页面申请麦克风权限
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceInputManager.hasPermission(this)) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, if (granted) R.string.voice_permission_granted else R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1001

        fun start(context: Context) {
            context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
