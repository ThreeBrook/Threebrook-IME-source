package com.yuyan.inputmethod

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln

/**
 * 英文拼写纠错：在词频表构成的字典树上做带权编辑距离搜索（插入、删除、替换、相邻字母交换）。
 * 键盘上相邻的字母打错代价更低，结果按"词频高、改动少"排序。
 * 词表：assets/english/en_words.txt（来源与授权见同目录 README.txt）
 */
object EnglishCorrector {
    private const val TAG = "EnglishCorrector"
    private const val ASSET = "english/en_words.txt"
    private const val MIN_LENGTH = 3 // 少于3个字母不纠错（缩写表除外）
    private const val ADJACENT_COST = 0.6 // 打成键盘上相邻的字母
    private const val SUBSTITUTE_COST = 1.0
    private const val INSERT_COST = 1.0
    private const val DELETE_COST = 1.0
    private const val TRANSPOSE_COST = 0.8 // 相邻两个字母打反
    private const val COST_WEIGHT = 4.0 // 每多一处改动，相当于词频差 e^4 倍
    private const val KNOWN_RATIO = 20 // 输入本身在词表里（词表里有常见错拼）时，只推荐常见20倍以上的词
    private const val RELATIVE_MIN = 0.01 // 词频不到最佳建议1%的（多为错拼、人名）不推荐

    // 词表按空格切词，没有带撇号的缩写，单独补上
    private val contractions = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "im" to "i'm", "ive" to "i've", "id" to "i'd",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd", "isnt" to "isn't",
        "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "didnt" to "didn't", "doesnt" to "doesn't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't", "couldnt" to "couldn't",
        "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "thats" to "that's", "whats" to "what's",
        "theres" to "there's", "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll",
        "weve" to "we've", "hes" to "he's", "shes" to "she's", "lets" to "let's", "itll" to "it'll",
    )

    private class Node {
        var keys = CharArray(0)
        var children = arrayOfNulls<Node>(0)
        var freq = 0
        var word: String? = null

        fun child(ch: Char): Node {
            val i = keys.indexOf(ch)
            if (i >= 0) return children[i]!!
            keys += ch
            return Node().also { children += it }
        }
    }

    private class Match(val word: String, val cost: Double, val freq: Int)

    @Volatile private var root: Node? = null
    private val frequencies = HashMap<String, Int>()
    private var loading = false

    /**
     * 后台加载词表（约4.7万词，几十毫秒到几百毫秒），加载完成前 suggest 只返回缩写表结果
     */
    @Synchronized
    fun preload(context: Context) {
        if (root != null || loading) return
        loading = true
        val appContext = context.applicationContext
        thread(name = TAG) {
            try {
                val newRoot = Node()
                appContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val space = line.indexOf(' ')
                        if (space <= 0) continue
                        val word = line.substring(0, space)
                        val freq = line.substring(space + 1).toIntOrNull() ?: continue
                        var node = newRoot
                        for (ch in word) node = node.child(ch)
                        node.freq = freq
                        node.word = word
                        frequencies[word] = freq
                    }
                }
                root = newRoot
                Log.i(TAG, "loaded ${frequencies.size} words")
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
            } finally {
                loading = false
            }
        }
    }

    /**
     * 返回最多 max 个纠错建议（小写）；拼写正确的常见词返回空
     */
    fun suggest(input: String, max: Int = 3): List<String> {
        val word = input.lowercase()
        val contraction = contractions[word]
        val trie = root
        if (word.length < MIN_LENGTH || trie == null || word.any { it !in 'a'..'z' }) return listOfNotNull(contraction)
        val maxCost = if (word.length <= 4) 1.0 else 2.0
        val matches = ArrayList<Match>()
        val firstRow = DoubleArray(word.length + 1) { it * INSERT_COST }
        for (i in trie.keys.indices) search(trie.children[i]!!, trie.keys[i], null, firstRow, null, word, maxCost, matches)

        val typedFreq = frequencies[word] ?: 0
        var results = matches.filter { it.freq >= typedFreq.toLong() * KNOWN_RATIO }
            .sortedByDescending { ln(it.freq.toDouble()) - it.cost * COST_WEIGHT }
        results.maxOfOrNull { it.freq }?.let { best -> results = results.filter { it.freq >= best * RELATIVE_MIN } }
        val words = results.map { it.word }
        return (if (contraction != null) listOf(contraction) + (words - contraction) else words).take(max)
    }

    private fun search(node: Node, ch: Char, prevCh: Char?, prevRow: DoubleArray, prevPrevRow: DoubleArray?,
                       word: String, maxCost: Double, matches: MutableList<Match>) {
        val row = DoubleArray(word.length + 1)
        row[0] = prevRow[0] + DELETE_COST
        var rowMin = row[0]
        for (j in 1..word.length) {
            val c = word[j - 1]
            val substitute = if (c == ch) 0.0 else if (isAdjacent(c, ch)) ADJACENT_COST else SUBSTITUTE_COST
            var value = minOf(row[j - 1] + INSERT_COST, prevRow[j] + DELETE_COST, prevRow[j - 1] + substitute)
            if (prevPrevRow != null && j > 1 && c == prevCh && word[j - 2] == ch) value = minOf(value, prevPrevRow[j - 2] + TRANSPOSE_COST)
            row[j] = value
            if (value < rowMin) rowMin = value
        }
        val nodeWord = node.word
        if (nodeWord != null && row[word.length] <= maxCost && nodeWord != word) matches.add(Match(nodeWord, row[word.length], node.freq))
        if (rowMin <= maxCost) {
            for (i in node.keys.indices) search(node.children[i]!!, node.keys[i], ch, row, prevRow, word, maxCost, matches)
        }
    }

    private val keyRows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private fun isAdjacent(a: Char, b: Char): Boolean {
        val rowA = keyRows.indexOfFirst { a in it }
        val rowB = keyRows.indexOfFirst { b in it }
        if (rowA < 0 || rowB < 0 || abs(rowA - rowB) > 1) return false
        // 每往下一行，键盘整体右移半个键
        val colA = keyRows[rowA].indexOf(a) + rowA * 0.5
        val colB = keyRows[rowB].indexOf(b) + rowB * 0.5
        return abs(colA - colB) <= 1.0
    }
}
