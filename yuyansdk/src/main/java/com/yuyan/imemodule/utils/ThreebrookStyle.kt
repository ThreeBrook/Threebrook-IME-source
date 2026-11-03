package com.yuyan.imemodule.utils

import android.content.Context
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.yuyan.imemodule.R

/**
 * 三溪外观规范的按钮：页面上最主要的动作用主按钮，删除、清空这类不能撤回的动作用危险按钮，
 * 其余直接 Button(context)，主题里默认就是描边按钮
 */
fun Context.primaryButton(): Button = Button(this, null, 0, R.style.Widget_Threebrook_Button_Primary)

fun Context.dangerButton(): Button = Button(this, null, 0, R.style.Widget_Threebrook_Button_Danger)

/**
 * 删除、清空的确认框：确认按钮用危险色，和“取消”区分开
 */
fun AlertDialog.Builder.showDanger(): AlertDialog = show().apply {
    getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(context, R.color.tb_danger))
}
