package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.inputmethod.UserLearning
import splitties.dimensions.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的词库：查看、删除输入法学到的词
 */
class LearnedWordsFragment : Fragment() {

    private lateinit var summary: TextView
    private val adapter = WordsAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        summary = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearButton = Button(context).apply {
            setText(R.string.learned_words_clear)
            setOnClickListener { confirmClear() }
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(summary)
            addView(clearButton)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            adapter = this@LearnedWordsFragment.adapter
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.words = UserLearning.allWords()
        summary.text = getString(R.string.learned_words_summary, adapter.words.size)
    }

    private fun confirmDelete(word: UserLearning.Word) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.learned_words_delete_confirm, word.text))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                UserLearning.delete(word.key, word.text)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (adapter.words.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.learned_words_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                UserLearning.clear()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class WordsAdapter : RecyclerView.Adapter<WordsAdapter.Holder>() {
        var words: List<UserLearning.Word> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        inner class Holder(val title: TextView, val detail: TextView, root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val title = TextView(context).apply { textSize = 18f }
            val detail = TextView(context).apply { textSize = 13f; alpha = 0.7f }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                addView(title)
                addView(detail)
            }
            return Holder(title, detail, root)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val word = words[position]
            val now = System.currentTimeMillis()
            val level = when {
                word.scoreAt(now) >= UserLearning.TOP_SCORE -> getString(R.string.learned_words_level_top)
                word.scoreAt(now) >= UserLearning.MIN_SCORE -> getString(R.string.learned_words_level_second)
                else -> getString(R.string.learned_words_level_forgotten)
            }
            holder.title.text = word.text
            holder.detail.text = getString(R.string.learned_words_detail, word.key, word.count, dateFormat.format(Date(word.lastUsed)), level)
            holder.itemView.setOnClickListener { confirmDelete(word) }
        }

        override fun getItemCount() = words.size
    }
}
