package com.yuyan.inputmethod

import android.content.Context
import android.util.Log
import com.yuyan.inputmethod.core.CandidateListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

/**
 * 用户词学习，替代Rime自带的学习（Rime的学习速度、遗忘都写死在引擎里，误选一次就能排到第一）。
 * 规则：
 * - 每次整段上屏，记一条"拼音 -> 上屏的词"，逐字拼出的新词按整词记录
 * - 门槛：权重达到 TOP_SCORE（约3次）才可能排第一，否则最多排第二
 * - 遗忘：权重每 HALF_LIFE_DAYS 天减半，低于 MIN_SCORE 不再参与排序
 * - 撤销：上屏后紧接着按删除键删掉刚上屏的词，这次不算
 * 数据存在 files/learning.tsv，启动时读入内存，修改后延迟写回
 */
object UserLearning {
    private const val TAG = "UserLearning"
    private const val FILE_NAME = "learning.tsv"
    const val TOP_SCORE = 2.5 // 3次使用（考虑少量衰减）才能排第一
    const val MIN_SCORE = 0.5 // 低于此权重视为已遗忘
    const val MAX_TEXT_LENGTH = 20 // 超过这个长度的上屏内容不学习
    private const val PRUNE_SCORE = 0.1 // 低于此权重时从文件中删除
    private const val HALF_LIFE_DAYS = 30.0
    private const val HALF_LIFE_MS = HALF_LIFE_DAYS * 24 * 3600 * 1000
    private const val UNDO_WINDOW_MS = 30_000L // 上屏后多久内删除算撤销
    private const val SAVE_DELAY_MS = 2_000L
    private const val ENGINE_USER_DB = "pinyin.userdb"

    class Word(val key: String, val text: String, var score: Double, var lastUsed: Long, var count: Int) {
        fun scoreAt(now: Long): Double = score * 0.5.pow((now - lastUsed) / HALF_LIFE_MS)
    }

    private class Undo(val key: String, val text: String, val previous: Word?, val time: Long)

    private val words = HashMap<String, MutableMap<String, Word>>() // key -> (text -> Word)
    private var file: File? = null
    private var pendingUndo: Undo? = null
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
                val parts = line.split('\t')
                if (parts.size == 5) {
                    val word = Word(parts[0], parts[1], parts[2].toDouble(), parts[3].toLong(), parts[4].toInt())
                    words.getOrPut(word.key) { HashMap() }[word.text] = word
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    /**
     * 关掉Rime自带的学习，引擎打不开学习库就只按系统词库排序。
     * 占位方式：学习库目录里的 LOCK 换成非空目录。LevelDB 建不了锁文件因而打不开，
     * Rime 的修复流程也删不掉非空目录、重建不了，学习库在整个会话里都保持不可用。
     * 第一次关闭时把原学习库改名备份；之后出现的学习库只可能是引擎重建的空库，直接删除
     */
    fun disableEngineUserDict(rimeDir: File) {
        val db = File(rimeDir, ENGINE_USER_DB)
        val lock = File(db, "LOCK")
        if (lock.isDirectory) return
        val backup = File(rimeDir, "$ENGINE_USER_DB.bak")
        when {
            db.isFile -> db.delete() // 旧版占位文件
            db.isDirectory && !backup.exists() -> if (!db.renameTo(backup)) Log.e(TAG, "backup engine user db failed")
            db.isDirectory -> db.deleteRecursively()
        }
        File(rimeDir, "$ENGINE_USER_DB.old").takeIf { it.isFile }?.delete() // 引擎修复时挪走的旧版占位文件
        if (!lock.mkdirs() || !File(lock, "disabled-by-threebrook").createNewFile()) Log.e(TAG, "disable engine user db failed")
    }

    /**
     * 拼音串（候选栏上方显示的拼音）规范化为学习用的键；含已选汉字等非拼音字符时返回null
     */
    fun keyOf(composition: String): String? {
        val key = composition.lowercase().trim()
        if (key.isEmpty() || key.any { it !in 'a'..'z' && it != '\'' }) return null
        return key
    }

    /**
     * 记录一次上屏
     */
    @Synchronized
    fun record(key: String, text: String) {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH || text.contains('\t') || text.contains('\n')) return
        val now = System.currentTimeMillis()
        val byText = words.getOrPut(key) { HashMap() }
        val old = byText[text]
        pendingUndo = Undo(key, text, old?.let { Word(it.key, it.text, it.score, it.lastUsed, it.count) }, now)
        if (old == null) {
            byText[text] = Word(key, text, 1.0, now, 1)
        } else {
            old.score = old.scoreAt(now) + 1
            old.lastUsed = now
            old.count++
        }
        scheduleSave()
    }

    /**
     * 上屏后除删除外的任何操作都让撤销失效
     */
    fun clearPendingUndo() {
        pendingUndo = null
    }

    /**
     * 按删除键时调用：如果紧接着上屏后删除的正是刚上屏的词，撤销这次学习
     * @param textBeforeCursor 光标前的文字（删除生效前）
     */
    @Synchronized
    fun onDeleteAfterCommit(textBeforeCursor: CharSequence?): Boolean {
        val undo = pendingUndo ?: return false
        pendingUndo = null
        if (System.currentTimeMillis() - undo.time > UNDO_WINDOW_MS) return false
        if (textBeforeCursor == null || !textBeforeCursor.endsWith(undo.text)) return false
        val byText = words[undo.key] ?: return false
        if (undo.previous == null) byText.remove(undo.text) else byText[undo.text] = undo.previous
        if (byText.isEmpty()) words.remove(undo.key)
        scheduleSave()
        Log.i(TAG, "undo learning ${undo.key} -> ${undo.text}")
        return true
    }

    /**
     * 按学习结果调整候选顺序。列表元素为 (候选, Rime候选下标)，学到但不在Rime候选里的词以下标-1插入
     * 权重够 TOP_SCORE 的按权重排在最前，其余最多排到第二位；本来就更靠前的词不往后挪
     * @return 插入的学习词
     */
    @Synchronized
    fun rank(key: String, items: MutableList<Pair<CandidateListItem, Int>>, injectedComment: String): Set<String> {
        val byText = words[key] ?: return emptySet()
        val now = System.currentTimeMillis()
        val learned = byText.values.map { it to it.scoreAt(now) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        if (learned.isEmpty()) return emptySet()
        val injected = HashSet<String>()
        var nextTop = 0
        var nextSecond = 1
        for ((word, score) in learned) {
            val target = if (score >= TOP_SCORE) nextTop else nextSecond.coerceAtMost(items.size)
            val current = items.indexOfFirst { it.first.text == word.text }
            when {
                current in 0..target -> {} // 本来就够靠前
                current > target -> items.add(target, items.removeAt(current))
                else -> {
                    items.add(target, CandidateListItem(injectedComment, word.text) to -1)
                    injected.add(word.text)
                }
            }
            if (score >= TOP_SCORE) nextTop++
            nextSecond++
        }
        return injected
    }

    /**
     * 我的词库：当前有效的学习词，按最近使用排序
     */
    @Synchronized
    fun allWords(): List<Word> = words.values.flatMap { it.values }.sortedByDescending { it.lastUsed }

    @Synchronized
    fun delete(key: String, text: String) {
        words[key]?.let {
            it.remove(text)
            if (it.isEmpty()) words.remove(key)
        }
        pendingUndo = null
        scheduleSave()
    }

    @Synchronized
    fun clear() {
        words.clear()
        pendingUndo = null
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
        val now = System.currentTimeMillis()
        try {
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { out ->
                for (word in words.values.flatMap { it.values }) {
                    if (word.scoreAt(now) < PRUNE_SCORE) continue
                    out.write("${word.key}\t${word.text}\t${word.score}\t${word.lastUsed}\t${word.count}\n")
                }
            }
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }
}
