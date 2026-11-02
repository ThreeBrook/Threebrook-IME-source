package com.yuyan.inputmethod

import android.util.Log
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 双语学习模式：打中文时候选后面显示外语释义，打外语时显示中文释义。
 * 词典是离线小词典（assets/bilingual/，由 tools/dict/bilingual.py 生成），开启时在后台读入内存，
 * 没读完之前查不到就不显示，不会卡住候选。关闭时不读词典，候选栏只多一次布尔判断。
 * 目标语言目前只有英语，换语言只需加一个 Target 和对应的两个词典文件。
 */
object Bilingual {
    private const val TAG = "Bilingual"
    private const val GLOSS_MAX = 14 // 候选后面的释义最多显示几个字符（中文释义按一半算）
    private val PAREN = Regex("\\([^)]*\\)")
    private val POS = Regex("^([a-z]+\\.\\s*)+")
    private val TAG_BRACKET = Regex("\\[[^\\]]*\\]")

    enum class Target(val code: String, val locale: Locale) {
        EN("en", Locale.US);

        val toForeign get() = "bilingual/zh-$code.tsv" // 中文 -> 外语
        val toChinese get() = "bilingual/$code-zh.tsv" // 外语 -> 中文

        companion object {
            fun of(code: String) = entries.firstOrNull { it.code == code } ?: EN
        }
    }

    /**
     * 一条词典释义
     * @param chinese 词条本身是不是中文（中文词条的释义是外语，外语词条的释义是中文）
     * @param readings 读音（拼音或音标）和对应的几条释义
     */
    class Entry(val word: String, val chinese: Boolean, val readings: List<Pair<String, List<String>>>) {
        val reading get() = readings.joinToString(" / ") { it.first }.takeIf { it.isNotBlank() }
        val summary get() = readings.firstOrNull()?.second?.firstOrNull().orEmpty()
    }

    @Volatile var enabled = false
        private set
    var target = Target.EN
        private set
    @Volatile private var toForeign: Map<String, String>? = null
    @Volatile private var toChinese: Map<String, String>? = null
    private var loading = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value -> update(value) }

    fun init() {
        val prefs = AppPrefs.getInstance().internal
        prefs.bilingualMode.registerOnChangeListener(enabledListener)
        update(prefs.bilingualMode.getValue())
    }

    private fun update(value: Boolean) {
        enabled = value
        if (value) preload() else {
            toForeign = null // 关闭后释放内存
            toChinese = null
        }
    }

    @Synchronized
    private fun preload() {
        if (loading || toForeign != null) return
        loading = true
        target = Target.of(AppPrefs.getInstance().internal.bilingualTarget.getValue())
        scope.launch {
            val start = System.currentTimeMillis()
            val foreign = load(target.toForeign)
            val chinese = load(target.toChinese)
            synchronized(this@Bilingual) {
                if (enabled) {
                    toForeign = foreign
                    toChinese = chinese
                }
                loading = false
            }
            Log.i(TAG, "loaded ${foreign.size}+${chinese.size} in ${System.currentTimeMillis() - start}ms")
        }
    }

    private fun load(asset: String): Map<String, String> {
        val map = HashMap<String, String>(65536)
        try {
            Launcher.instance.context.assets.open(asset).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load $asset failed", e)
        }
        return map
    }

    private fun isChinese(text: String) = text.any { it in '一'..'鿿' || it in '㐀'..'䶿' }

    private fun isForeignWord(text: String) = text.isNotEmpty() && text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-' }

    /**
     * 查词，词典没读完或查不到时返回null
     */
    fun lookup(text: String): Entry? {
        if (!enabled) return null
        val word = text.trim()
        return when {
            isChinese(word) -> toForeign?.get(word)?.let { parse(word, true, it) }
            isForeignWord(word) -> (toChinese?.get(word) ?: toChinese?.get(word.lowercase()))?.let { parse(word, false, it) }
            else -> null
        }
    }

    private fun parse(word: String, chinese: Boolean, raw: String): Entry {
        val fields = raw.split('\t')
        val readings = if (chinese) {
            // 读音 \t 释义(/分隔) 成对出现
            fields.chunked(2).filter { it.size == 2 }.map { it[0] to it[1].split('/') }
        } else {
            // 音标 \t 释义行...
            listOf(fields[0] to fields.drop(1))
        }
        return Entry(word, chinese, readings)
    }

    /**
     * 候选后面显示的简短释义
     */
    fun gloss(text: String): String? {
        if (!enabled) return null
        val entry = lookup(text) ?: return null
        val first = entry.summary
        val short = if (entry.chinese) {
            // "(modal particle) to be; correct" -> "to be"；整条都在括号里时去掉括号保留内容
            val noParen = first.replace(PAREN, "").trim()
            (noParen.ifEmpty { first.trim('(', ')', ' ') }).split(';').first().trim()
        } else {
            // "n. 苹果, 家伙" -> "苹果"
            first.replace(POS, "").replace(TAG_BRACKET, "")
                .split(',', '，', ';', '；').first().trim()
        }
        if (short.isEmpty()) return null
        val max = if (entry.chinese) GLOSS_MAX else GLOSS_MAX / 2
        return if (short.length > max) short.take(max - 1) + "…" else short
    }
}
