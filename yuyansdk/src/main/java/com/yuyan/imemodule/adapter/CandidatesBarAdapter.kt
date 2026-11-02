package com.yuyan.imemodule.adapter

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.inputmethod.Bilingual

/**
 * 候选词界面适配器
 */
class CandidatesBarAdapter(context: Context?) :
    RecyclerView.Adapter<CandidatesBarAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mActiveCandNo:Int = 0
    var onItemLongClick: ((position: Int) -> Unit)? = null // 双语学习模式：长按候选看详情
    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    init {
        mActiveCandNo = 0
        inflater = LayoutInflater.from(context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.sdk_item_recyclerview_candidates_bar, parent, false)
        return SymbolHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        if(DictDecoder.isCandidatesEmpty) return
        val text = DictDecoder.candidates[position].text
        val color = if(mActiveCandNo-1 == position) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
        holder.textView.setTextColor(color)
        if (Bilingual.enabled) {
            val gloss = Bilingual.gloss(text)
            holder.textView.text = if (gloss == null) text else withGloss(text, gloss, color)
            holder.textView.setOnLongClickListener {
                onItemLongClick?.invoke(holder.bindingAdapterPosition)
                true
            }
            holder.longClickSet = true
        } else {
            holder.textView.text = text
            if (holder.longClickSet) {
                holder.textView.setOnLongClickListener(null)
                holder.textView.isLongClickable = false
                holder.longClickSet = false
            }
        }
        if (mOnItemClickListener != null) {
            holder.textView.setOnClickListener { view: View? ->
                mOnItemClickListener!!.onItemClick(this@CandidatesBarAdapter, view, position)
            }
        }
    }

    override fun getItemCount(): Int {
        return DictDecoder.candidateSize
    }

    /** 候选后面接一小段淡色释义 */
    private fun withGloss(text: String, gloss: String, color: Int): CharSequence {
        val span = SpannableString("$text $gloss")
        span.setSpan(RelativeSizeSpan(0.6f), text.length, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan((color and 0x00FFFFFF) or 0x99000000.toInt()), text.length, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    fun activeCandidates(activeCandNo:Int) {
        mActiveCandNo = activeCandNo
    }

    fun notifyChanged() {
        notifyDataSetChanged()
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_candidates_bar_item)
        var longClickSet = false
        init {
            textView.setTextColor(activeTheme.keyTextColor)
        }
    }
}
