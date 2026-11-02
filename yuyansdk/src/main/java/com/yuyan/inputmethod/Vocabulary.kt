package com.yuyan.inputmethod

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 生词本：双语学习模式里长按候选加入的词，带简单的间隔复习。
 * 复习规则：新词马上可以复习；记得 -> 间隔翻 2.5 倍（第一次 1 天，最长 180 天）；忘了 -> 间隔清零，10 分钟后再复习
 * 数据存在 files/vocabulary.tsv
 */
object Vocabulary {
    private const val TAG = "Vocabulary"
    private const val FILE_NAME = "vocabulary.tsv"
    private const val DAY_MS = 24 * 3600 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val MAX_INTERVAL_DAYS = 180.0
    private const val SAVE_DELAY_MS = 1_000L

    /**
     * @param lang 词条的语言：zh 或目标语言代码（如 en）
     * @param meaning 释义，多条用 / 分隔
     */
    class Word(
        val text: String, val lang: String, val reading: String, val meaning: String,
        val added: Long, var due: Long, var intervalDays: Double, var reviews: Int,
    ) {
        val chinese get() = lang == "zh"
    }

    private val words = LinkedHashMap<String, Word>()
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, FILE_NAME)
        file = target
        if (!target.exists()) return
        try {
            target.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 8) words[p[0]] = Word(p[0], p[1], p[2], p[3], p[4].toLong(), p[5].toLong(), p[6].toDouble(), p[7].toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    fun add(entry: Bilingual.Entry) {
        if (words.containsKey(entry.word)) return
        val meaning = entry.readings.flatMap { it.second }.take(6).joinToString("/")
        val now = System.currentTimeMillis()
        words[entry.word] = Word(clean(entry.word), if (entry.chinese) "zh" else Bilingual.target.code, clean(entry.reading.orEmpty()), clean(meaning), now, now, 0.0, 0)
        scheduleSave()
    }

    @Synchronized
    fun contains(text: String) = words.containsKey(text)

    /** 全部生词，最近加入的在前 */
    @Synchronized
    fun all(): List<Word> = words.values.sortedByDescending { it.added }

    /** 到期要复习的词，最早到期的在前 */
    @Synchronized
    fun due(now: Long = System.currentTimeMillis()): List<Word> = words.values.filter { it.due <= now }.sortedBy { it.due }

    @Synchronized
    fun review(word: Word, remembered: Boolean) {
        val now = System.currentTimeMillis()
        if (remembered) {
            word.intervalDays = if (word.intervalDays < 1) 1.0 else (word.intervalDays * 2.5).coerceAtMost(MAX_INTERVAL_DAYS)
            word.due = now + (word.intervalDays * DAY_MS).toLong()
        } else {
            word.intervalDays = 0.0
            word.due = now + RETRY_MS
        }
        word.reviews++
        scheduleSave()
    }

    @Synchronized
    fun delete(text: String) {
        words.remove(text)
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    @Synchronized
    private fun save() {
        val target = file ?: return
        try {
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { out ->
                for (w in words.values) {
                    out.write("${w.text}\t${w.lang}\t${w.reading}\t${w.meaning}\t${w.added}\t${w.due}\t${w.intervalDays}\t${w.reviews}\n")
                }
            }
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}
