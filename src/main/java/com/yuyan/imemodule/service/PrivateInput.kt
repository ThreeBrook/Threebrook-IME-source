package com.yuyan.imemodule.service

import android.content.ClipDescription
import android.view.inputmethod.EditorInfo

/**
 * 敏感输入框：密码框，以及应用要求不做个性化学习的输入框（如浏览器无痕模式）。
 * 在这类输入框里打字时不学习、不记录剪贴板，见隐私政策第 5 条。
 */
object PrivateInput {
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE，API 33

    @Volatile
    var active = false
        private set

    fun update(info: EditorInfo?) {
        active = info != null && isPrivate(info)
    }

    fun isPrivate(info: EditorInfo): Boolean {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (info.inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * 复制内容被标为敏感（密码管理器等在 Android 13 起会这样标）
     */
    fun isSensitiveClip(description: ClipDescription?): Boolean =
        description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
}
