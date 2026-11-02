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
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.utils.Speaker
import com.yuyan.inputmethod.Bilingual
import com.yuyan.inputmethod.Vocabulary
import splitties.dimensions.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 双语学习：模式开关、生词本（查看、删除）、间隔复习
 */
class BilingualFragment : Fragment() {

    private lateinit var summary: TextView
    private lateinit var reviewButton: Button
    private val adapter = WordsAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        Vocabulary.init(context)
        val pref = AppPrefs.getInstance().internal.bilingualMode
        val switch = SwitchCompat(context).apply {
            setText(R.string.bilingual_mode)
            textSize = 17f
            isChecked = pref.getValue()
            setOnCheckedChangeListener { _, checked -> pref.setValue(checked) }
        }
        val intro = TextView(context).apply {
            setText(R.string.bilingual_mode_summary)
            alpha = 0.7f
        }
        val target = TextView(context).apply {
            text = getString(R.string.bilingual_target_language, getString(R.string.bilingual_language_en))
            setPadding(0, dp(8), 0, 0)
        }
        val settings = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(switch)
            addView(intro)
            addView(target)
        }
        summary = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        reviewButton = Button(context).apply {
            setOnClickListener { startReview() }
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            addView(summary)
            addView(reviewButton)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            adapter = this@BilingualFragment.adapter
        }
        val license = TextView(context).apply {
            setText(R.string.bilingual_license)
            textSize = 11f
            alpha = 0.6f
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(settings)
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(license)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.words = Vocabulary.all()
        summary.text = getString(R.string.vocabulary_summary, adapter.words.size)
        val due = Vocabulary.due().size
        reviewButton.text = getString(R.string.vocabulary_review, due)
        reviewButton.isEnabled = due > 0
    }

    private fun locale(word: Vocabulary.Word) =
        if (word.chinese) Locale.SIMPLIFIED_CHINESE else Bilingual.Target.of(word.lang).locale

    /** 复习：先只看词，想一想再看释义，然后选记得或忘了 */
    private fun startReview() {
        val queue = Vocabulary.due()
        if (queue.isNotEmpty()) showQuestion(queue, 0)
    }

    private fun showQuestion(queue: List<Vocabulary.Word>, index: Int) {
        if (index >= queue.size || !isAdded) {
            refresh()
            return
        }
        val word = queue[index]
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.vocabulary_review_progress, index + 1, queue.size))
            .setMessage(word.text)
            .setPositiveButton(R.string.vocabulary_show_answer) { _, _ -> showAnswer(queue, index) }
            .setNeutralButton(R.string.bilingual_speak, null)
            .setNegativeButton(R.string.vocabulary_review_stop) { _, _ -> refresh() }
            .setCancelable(false)
            .show()
            .apply {
                // 发音按钮点了不关闭对话框
                getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { Speaker.speak(requireContext(), word.text, locale(word)) }
            }
    }

    private fun showAnswer(queue: List<Vocabulary.Word>, index: Int) {
        val word = queue[index]
        val meaning = word.meaning.split('/').joinToString("\n") { "· $it" }
        AlertDialog.Builder(requireContext())
            .setTitle(listOf(word.text, word.reading).filter { it.isNotBlank() }.joinToString("  "))
            .setMessage(meaning)
            .setPositiveButton(R.string.vocabulary_remembered) { _, _ ->
                Vocabulary.review(word, true)
                showQuestion(queue, index + 1)
            }
            .setNegativeButton(R.string.vocabulary_forgot) { _, _ ->
                Vocabulary.review(word, false)
                showQuestion(queue, index + 1)
            }
            .setCancelable(false)
            .show()
    }

    private fun confirmDelete(word: Vocabulary.Word) {
        AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.learned_words_delete_confirm, word.text))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Vocabulary.delete(word.text)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class WordsAdapter : RecyclerView.Adapter<WordsAdapter.Holder>() {
        var words: List<Vocabulary.Word> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        inner class Holder(val title: TextView, val meaning: TextView, val detail: TextView, root: View) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val title = TextView(context).apply { textSize = 18f }
            val meaning = TextView(context).apply { textSize = 14f; maxLines = 2 }
            val detail = TextView(context).apply { textSize = 12f; alpha = 0.6f }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                addView(title)
                addView(meaning)
                addView(detail)
            }
            return Holder(title, meaning, detail, root)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val word = words[position]
            holder.title.text = listOf(word.text, word.reading).filter { it.isNotBlank() }.joinToString("  ")
            holder.meaning.text = word.meaning.replace("/", "；")
            holder.detail.text = if (word.due <= System.currentTimeMillis()) {
                getString(R.string.vocabulary_detail_due, word.reviews)
            } else {
                getString(R.string.vocabulary_detail, word.reviews, dateFormat.format(Date(word.due)))
            }
            holder.itemView.setOnClickListener { confirmDelete(word) }
        }

        override fun getItemCount() = words.size
    }
}
