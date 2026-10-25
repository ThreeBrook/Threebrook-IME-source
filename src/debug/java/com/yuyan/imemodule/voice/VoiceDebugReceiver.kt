package com.yuyan.imemodule.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 仅调试版：用音频文件测试离线识别（模拟器里没法真的对着麦克风说话）
 * adb shell am broadcast -n <包名>/com.yuyan.imemodule.voice.VoiceDebugReceiver --es wav <16kHz单声道16位wav路径>
 * 结果输出到 logcat，标签 VoiceDebug
 */
class VoiceDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("wav") ?: return
        val appContext = context.applicationContext
        Log.i(TAG, "test $path")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val audio = readWav(File(path))
                val start = SystemClock.elapsedRealtime()
                val text = VoiceInputManager.recognize(appContext, audio)
                Log.i(TAG, "audio=${audio.size / 16000f}s cost=${SystemClock.elapsedRealtime() - start}ms result=$text")
            } catch (e: Throwable) {
                Log.e(TAG, "test failed", e)
            }
        }
    }

    private fun readWav(file: File): FloatArray {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(12)
        while (bytes.remaining() >= 8) {
            val id = ByteArray(4).also { bytes.get(it) }.toString(Charsets.US_ASCII)
            val size = bytes.int
            if (id == "data") return FloatArray(size / 2) { bytes.short / 32768f }
            bytes.position(bytes.position() + size)
        }
        error("no data chunk in $file")
    }

    companion object {
        private const val TAG = "VoiceDebug"
    }
}
