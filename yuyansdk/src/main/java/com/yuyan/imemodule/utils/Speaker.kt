package com.yuyan.imemodule.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.yuyan.imemodule.R
import java.util.Locale

/**
 * 发音：用系统自带的文字转语音（TTS）。第一次用到时才连接 TTS 引擎，连接完成前点的发音排队等待
 */
object Speaker {
    private const val TAG = "Speaker"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    fun speak(context: Context, text: String, locale: Locale) {
        val engine = tts
        if (engine == null) {
            pending = text to locale
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (!ready) {
                    Log.e(TAG, "tts init failed: $status")
                    Toast.makeText(context.applicationContext, R.string.bilingual_tts_unavailable, Toast.LENGTH_SHORT).show()
                    tts?.shutdown()
                    tts = null
                }
                pending?.let { (t, l) -> if (ready) say(context, t, l) }
                pending = null
            }
        } else if (ready) {
            say(context, text, locale)
        } else {
            pending = text to locale
        }
    }

    private fun say(context: Context, text: String, locale: Locale) {
        val engine = tts ?: return
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(context.applicationContext, R.string.bilingual_tts_no_language, Toast.LENGTH_SHORT).show()
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bilingual")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }
}
