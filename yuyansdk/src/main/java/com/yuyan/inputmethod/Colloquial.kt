package com.yuyan.inputmethod

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.inputmethod.core.CandidateListItem

/**
 * 日常口语加权：九宫格同一串按键常对应好几个词（"这些"和"谢谢"都是 943943），
 * 词库按书面语词频排序，聊天常用的词容易排到后面。口语词表里的词和覆盖同样按键的候选撞车时排到前面。
 * 只在覆盖同样按键的候选之间调整，整句、只打了一部分的候选位置不受影响。
 * 词表：assets/colloquial/words.txt
 */
object Colloquial {
    private const val TAG = "Colloquial"
    private const val ASSET = "colloquial/words.txt"
    private const val WINDOW = 12 // 只调整前面这些候选，后面的翻页才看得到，不值得算

    private val words: Set<String> by lazy {
        try {
            Launcher.instance.context.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.length >= 2 && !it.startsWith('#') }.toHashSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            emptySet()
        }
    }

    /**
     * 候选覆盖的输入长度：注释里是候选的拼音（如 fa'gei'ni），九宫格一个字母一次按键，按字母数算；
     * 没有拼音注释（或是补全候选 ~xxx）时按字数算，用负数和字母数区分开
     */
    private fun span(item: CandidateListItem): Int =
        item.comment.takeIf { it.isNotBlank() && !it.startsWith("~") }?.count { it in 'a'..'z' } ?: -item.text.length

    /**
     * 口语词往前挪到第一个覆盖同样长度的非口语候选之前（"发给你"和"待定"在九宫格都是 3243464），口语词之间保持原顺序
     */
    fun boost(items: MutableList<IndexedValue<CandidateListItem>>) {
        for (i in 1 until items.size.coerceAtMost(WINDOW)) {
            val item = items[i].value
            if (item.text !in words) continue
            val span = span(item)
            val target = (0 until i).firstOrNull { span(items[it].value) == span && items[it].value.text !in words } ?: continue
            items.add(target, items.removeAt(i))
        }
    }
}
