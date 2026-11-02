package com.yuyan.imemodule.view

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.utils.Speaker
import com.yuyan.inputmethod.Bilingual
import com.yuyan.inputmethod.Vocabulary
import splitties.dimensions.dp
import java.util.Locale

/**
 * 双语学习模式：长按候选弹出的详情卡片，盖在键盘上。显示读音、释义，可以发音、加入生词本
 */
object BilingualCard {
    private var popup: PopupWindow? = null

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    /**
     * @param anchor 候选栏，卡片显示在它下面
     * @param maxHeight 卡片最大高度（键盘高度）
     */
    fun show(anchor: View, entry: Bilingual.Entry, maxHeight: Int) {
        dismiss()
        val context = anchor.context
        val textColor = activeTheme.keyTextColor
        val foreignLocale = Bilingual.target.locale
        val headLocale = if (entry.chinese) Locale.SIMPLIFIED_CHINESE else foreignLocale

        val title = TextView(context).apply {
            text = entry.word
            textSize = 24f
            setTextColor(textColor)
        }
        val reading = TextView(context).apply {
            text = entry.reading.orEmpty()
            textSize = 15f
            setTextColor(textColor)
            alpha = 0.7f
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val speak = Button(context).apply {
            setText(R.string.bilingual_speak)
            setOnClickListener { Speaker.speak(context, entry.word, headLocale) }
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(reading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(speak)
        }

        val meanings = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((index, pair) in entry.readings.withIndex()) {
            val (sound, defs) = pair
            if (entry.readings.size > 1) meanings.addView(TextView(context).apply {
                text = sound
                textSize = 14f
                setTextColor(textColor)
                alpha = 0.7f
                setPadding(0, if (index == 0) 0 else dp(6), 0, 0)
            })
            for (def in defs) meanings.addView(TextView(context).apply {
                text = context.getString(R.string.bilingual_meaning_line, def)
                textSize = 16f
                setTextColor(textColor)
                setPadding(0, dp(2), 0, dp(2))
                // 中文词条的释义是外语，点一下读出来
                if (entry.chinese) setOnClickListener { Speaker.speak(context, def.replace(Regex("\\([^)]*\\)"), ""), foreignLocale) }
            })
        }
        val scroll = ScrollView(context).apply { addView(meanings) }

        val add = Button(context)
        fun updateAdd() {
            val saved = Vocabulary.contains(entry.word)
            add.setText(if (saved) R.string.bilingual_in_vocabulary else R.string.bilingual_add_vocabulary)
            add.isEnabled = !saved
        }
        updateAdd()
        add.setOnClickListener {
            Vocabulary.add(entry)
            updateAdd()
        }
        val close = Button(context).apply {
            setText(R.string.bilingual_close)
            setOnClickListener { dismiss() }
        }
        val footer = LinearLayout(context).apply {
            gravity = Gravity.END
            addView(add)
            addView(close)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            background = GradientDrawable().apply {
                setColor(activeTheme.keyboardColor or 0xFF000000.toInt())
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), (textColor and 0x00FFFFFF) or 0x33000000)
            }
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer)
        }
        popup = PopupWindow(root, anchor.width, maxHeight, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // 有背景才能点外面关闭
            isOutsideTouchable = true
            isClippingEnabled = false
            setOnDismissListener { if (popup === this) popup = null }
            showAsDropDown(anchor, 0, 0)
        }
    }
}
