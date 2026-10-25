package com.yuyan.inputmethod

import android.view.KeyEvent
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.inputmethod.core.CandidateListItem
import com.yuyan.inputmethod.core.Rime
import com.yuyan.inputmethod.data.InputKey
import com.yuyan.inputmethod.data.KeyRecordStack
import com.yuyan.inputmethod.util.DoublePinYinUtils
import com.yuyan.inputmethod.util.LX17PinYinUtils
import com.yuyan.inputmethod.util.QwertyPinYinUtils
import com.yuyan.inputmethod.util.T9PinYinUtils
import java.util.Locale

object RimeEngine {
    private val keyRecordStack = KeyRecordStack()
    private var pinyins: Array<String> = emptyArray() // 候选词界面的候选拼音列表
    var showCandidates: List<CandidateListItem> = emptyList() // 所有待展示的候选词
    var showComposition: String = "" // 候选词上方展示的拼音
    var preCommitText: String = "" // 待提交的文字
    private var customPhraseSize: Int = 0 // 自定义引擎候选词长度
    private var rimeIndexMap: IntArray = IntArray(0) // 展示位置 -> Rime候选下标，自定义候选为-1
    private var learningKey: String? = null // 本次输入的学习键（尚未选字时的拼音）
    private var learnedInjected: Set<String> = emptySet() // 由学习插入、不在Rime候选里的词
    private const val DYNAMIC_PHRASE_POSITION = 1 // 日期时间排在首选之后
    private const val EMOJI_MIN_POSITION = 5 // 表情至少排在前5个文字候选之后
    const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000
    fun init() {
        Rime.getInstance(false)
    }

    fun selectSchema(mod: String): Boolean {
        keyRecordStack.clear()
        charCase = MASK_CASE_LOWER
        Rime.startup(Launcher.instance.context, false)
        return Rime.selectSchema(mod)
    }

    fun getCurrentRimeSchema(): String {
        return Rime.getCurrentRimeSchema()
    }

    /**
     * 是否输入完毕
     */
    fun isFinish(): Boolean {
        return keyRecordStack.isEmpty()
    }

    fun onNormalKey(event: KeyEvent) {
        val keyCode = event.keyCode
        val keyChar = if(keyCode == KeyEvent.KEYCODE_APOSTROPHE) if(isFinish()) '/'.code else '\''.code
            else event.unicodeChar
        if (keyRecordStack.pushKey(event))Rime.processKey(keyChar, event.action)
        updateCandidatesOrCommitText()
    }

    fun onDeleteKey() {
        keyRecordStack.processDelAction()
        updateCandidatesOrCommitText()
    }

    fun selectCandidate(index: Int): String? {
        // 翻页追加的候选不在映射表内，它们前面的自定义候选数量固定为customPhraseSize
        val indexReal = rimeIndexMap.getOrNull(index)?.takeIf { it >= 0 } ?: (index - customPhraseSize)
        Rime.selectCandidate(indexReal)
        keyRecordStack.pushCandidateSelectAction()
        return updateCandidatesOrCommitText()
    }

    fun getNextPageCandidates(): Array<CandidateListItem> {
        return if (Rime.hasRight()) {
            Rime.processKey(keyRecordStack.getRimeKeycodeByName("Page_Down"), 0)
           val candidates = Rime.getRimeContext()!!.candidates
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                }
                KeyEvent.META_CAPS_LOCK_ON -> {
                    for (item in candidates) {
                        item.text = item.text.uppercase()
                    }
                }
                else -> {
                    for (item in candidates) {
                        item.text = item.text.lowercase()
                    }
                }
            }
            candidates
        } else emptyArray()
    }

    fun selectPinyin(index: Int) {
        val pinyinKey = keyRecordStack.pushPinyinSelectAction(pinyins[index]) ?: return
        Rime.replaceKey(pinyinKey.posInInput, pinyinKey.t9Keys().length, pinyinKey.pinyin())
        updateCandidatesOrCommitText()
    }

    fun predictAssociationWords(text: String) {
        pinyins = emptyArray()
        if (text.isNotEmpty()) {
            showCandidates = buildList {
                val words = Rime.getAssociateList(text)
                val firstFive = words.take(5)
                addAll(firstFive.filterNotNull().map { CandidateListItem("", it) })
                addAll(CustomEngine.predictAssociationWordsChinese(text).map { CandidateListItem("", it) })
                val remaining = words.drop(5)
                addAll(remaining.filterNotNull().map { CandidateListItem("", it) })
            }
            showComposition = ""
        }
    }

    fun selectAssociation(index: Int) {
        val indexReal = index - customPhraseSize
        Rime.chooseAssociate(indexReal)
        updateCandidatesOrCommitText()
        preCommitText = showCandidates.getOrNull(indexReal)?.text?:""
    }

    /**
     * 组装候选：常用语在最前，日期时间排在首选之后，表情排在前几个文字候选之后。
     * 同时记录每个展示位置对应的Rime候选下标，供选词时换算
     */
    private fun arrangeCandidates(phrase: List<String>, dynamicPhrase: List<String>, candidates: List<CandidateListItem>, key: String?): List<CandidateListItem> {
        // 和自定义候选重复的Rime候选去掉（如英文纠错"don't"），下标映射随元素一起保留，不影响选词
        val custom = (phrase + dynamicPhrase).map { it.lowercase() }.toSet()
        val (emojis, words) = candidates.withIndex().filter { it.value.text.lowercase() !in custom }
            .partition { StringUtils.isEmoji(it.value.text) }
        val rimeOrdered = words.toMutableList().apply { addAll(EMOJI_MIN_POSITION.coerceAtMost(size), emojis) }
            .map { it.value to it.index }.toMutableList()
        learnedInjected = if (key != null) UserLearning.rank(key, rimeOrdered, "📋") else emptySet()
        val result = mutableListOf<Pair<CandidateListItem, Int>>()
        phrase.forEach { result.add(CandidateListItem("📋", it) to -1) }
        result.addAll(rimeOrdered)
        val dynamicPosition = (phrase.size + DYNAMIC_PHRASE_POSITION).coerceAtMost(result.size)
        result.addAll(dynamicPosition, dynamicPhrase.map { CandidateListItem("📋", it) to -1 })
        rimeIndexMap = result.map { it.second }.toIntArray()
        return result.map { it.first }
    }

    fun reset() {
        showCandidates = emptyList()
        pinyins = emptyArray()
        showComposition = ""
        preCommitText = ""
        learningKey = null
        learnedInjected = emptySet()
        keyRecordStack.clear()
        Rime.clearComposition()
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = Rime.destroy()

    /**
     * 点选了自定义候选（📋）上屏：如果是学习插入的词，同样记一次使用
     */
    fun onCustomCandidateCommitted(text: String) {
        if (text in learnedInjected) learningKey?.let { UserLearning.record(it, text) }
    }

    private fun updateCandidatesOrCommitText(): String? {
        val rimeCommit = Rime.getRimeCommit()
        if (rimeCommit != null) {
            keyRecordStack.clear()
            preCommitText = rimeCommit.commitText
            preCommitText = if (charCase == KeyEvent.META_SHIFT_ON) {
                preCommitText.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            } else if (charCase == KeyEvent.META_CAPS_LOCK_ON) {
                preCommitText.uppercase()
            } else {
                preCommitText.lowercase()
            }
            learningKey?.let { UserLearning.record(it, preCommitText) }
            learningKey = null
            showComposition = ""
            showCandidates = emptyList()
            return preCommitText
        }
        val candidates = Rime.getRimeContext()?.candidates?.asList() ?: emptyList()
        customPhraseSize = 0
        rimeIndexMap = IntArray(0)
        val compositionText = Rime.compositionText
        var count = Rime.compositionText.count { it in 'A'..'Z' }
        if (count > 0) {
            keyRecordStack.forEachReversed { inputKey ->
                if (inputKey is InputKey.T9Key) inputKey.consumed = count-- <= 0
            }
        }
        var composition = getCurrentComposition(candidates)
        // 还没选字时的拼音作为学习键；选过字后拼音里会混入汉字，沿用之前的键，整段上屏时按整词记录
        val key = if (InputModeSwitcher.isEnglish) null else UserLearning.keyOf(composition)
        if (key != null) learningKey = key
        showCandidates = when {
            compositionText.isNotBlank() -> {
                val code = compositionText.replace("\'", "")
                val phrase = CustomEngine.processPhrase(code)
                if(InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText) &&
                    !compositionText.equals(candidates.firstOrNull()?.text, ignoreCase = true) ){
                    phrase.add(0, compositionText)
                }
                val dynamicPhrase = CustomEngine.processDynamicPhrase(code)
                if (InputModeSwitcher.isEnglish && StringUtils.isLetter(compositionText)) {
                    // 拼写纠错排在原样输入之后，空格仍上屏原样输入；补全候选里的重复项在组装时去掉
                    val fixes = EnglishCorrector.suggest(compositionText)
                    val typedIndex = phrase.indexOfFirst { it.equals(compositionText, ignoreCase = true) }
                    // 原样输入就是首个补全候选时，纠错跟在它后面（动态候选的位置）
                    if (typedIndex >= 0) phrase.addAll(typedIndex + 1, fixes) else dynamicPhrase.addAll(0, fixes)
                }
                customPhraseSize = phrase.size + dynamicPhrase.size
                arrangeCandidates(phrase, dynamicPhrase, candidates, key)
            }
            else -> candidates
        }
        when (charCase) {
            KeyEvent.META_SHIFT_ON -> {
                for (item in showCandidates) item.text = item.text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                composition = composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            KeyEvent.META_CAPS_LOCK_ON -> {
                for (item in showCandidates) item.text = item.text.uppercase()
                composition = composition.uppercase()
            }
            else -> {
                for (item in showCandidates) item.text = item.text.lowercase()
                composition = composition.lowercase()
            }
        }
        val rimeSchema = Rime.getCurrentRimeSchema()
        pinyins = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.t9KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> {
                LX17PinYinUtils.lx17KeyToPinyin(Regex("[A-Z]+").find(compositionText.replace("'", ""))?.value ?: "")
            }
            else -> {
                emptyArray()
            }
        }
        showComposition = composition
        preCommitText = ""
        return null
    }

    /**
     * 拿到候选词拼音组合
     */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(candidates: List<CandidateListItem>): String {
        val composition = Rime.compositionText
        val rimeSchema = Rime.getCurrentRimeSchema()
        if(rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if(composition.isEmpty()) return ""
        if(candidates.isEmpty()) return composition
        val comment = candidates.first().comment
        val result =  when {
            comment.isNotBlank() && comment.startsWith("~") -> composition
            rimeSchema == CustomConstant.SCHEMA_ZH_T9 -> {
                T9PinYinUtils.getT9Composition(composition, comment)
            }
            rimeSchema.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) -> {
                if(!AppPrefs.getInstance().keyboardSetting.keyboardDoubleInputKey.getValue()) composition
                else DoublePinYinUtils.getDoublePinYinComposition(rimeSchema, composition, comment)
            }
            else -> {
                QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    /**
     * 设置输入法搜索参数
     */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setOption(option, value)
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

}