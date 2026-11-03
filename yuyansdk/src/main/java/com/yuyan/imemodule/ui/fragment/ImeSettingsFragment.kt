package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.utils.addCategory
import com.yuyan.imemodule.utils.addPreference
import androidx.core.net.toUri
import com.yuyan.imemodule.ui.setup.SetupActivity

class ImeSettingsFragment : PreferenceFragmentCompat() {

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        @IdRes destination: Int
    ) {
        addPreference(title, icon = icon) {
            findNavController().navigate(destination)
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.input_methods) {
                isIconSpaceReserved = false

                if (SetupActivity.shouldShowUp()) {
                    addDestinationPreference(
                        R.string.enable_ime,
                        R.drawable.tb_ic_toggle_on,
                        R.id.action_settingsFragment_to_setupActivity
                    )
                }
                addDestinationPreference(
                    R.string.setting_ime_input,
                    R.drawable.tb_ic_tune,
                    R.id.action_settingsFragment_to_inputSettingsFragment
                )
                addDestinationPreference(
                    R.string.ime_settings_handwriting,
                    R.drawable.tb_ic_draw,
                    R.id.action_settingsFragment_to_handwritingSettingsFragment
                )
                addDestinationPreference(
                    R.string.ime_settings_voice,
                    R.drawable.tb_ic_mic,
                    R.id.action_settingsFragment_to_voiceSettingsFragment
                )
                addDestinationPreference(
                    R.string.learned_words,
                    R.drawable.tb_ic_menu_book,
                    R.id.action_settingsFragment_to_learnedWordsFragment
                )
                addDestinationPreference(
                    R.string.bilingual,
                    R.drawable.tb_ic_translate,
                    R.id.action_settingsFragment_to_bilingualFragment
                )
            }
            addCategory(R.string.keyboard) {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.tb_ic_palette,
                    R.id.action_settingsFragment_to_themeSettingsFragment
                )
                addDestinationPreference(
                    R.string.keyboard_feedback,
                    R.drawable.tb_ic_vibration,
                    R.id.action_settingsFragment_to_keyboardFeedbackFragment
                )
                addDestinationPreference(
                    R.string.setting_ime_keyboard,
                    R.drawable.tb_ic_keyboard,
                    R.id.action_settingsFragment_to_keyboardSettingFragment
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.tb_ic_content_paste,
                    R.id.action_settingsFragment_to_clipboardSettingsFragment
                )

                addDestinationPreference(
                    R.string.full_display_keyboard,
                    R.drawable.tb_ic_fit_screen,
                    R.id.action_settingsFragment_to_fullDisplayKeyboardFragment
                )
            }
            addCategory(R.string.advanced) {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.setting_ime_other,
                    R.drawable.tb_ic_more_horiz,
                    R.id.action_settingsFragment_to_otherSettingsFragment
                )
                addPreference(R.string.feedback,"",
                    R.drawable.tb_ic_mail,) {
                    try {
                        startActivity(Intent(Intent.ACTION_SENDTO, "mailto:${CustomConstant.THREEBROOK_SUPPORT_EMAIL}".toUri()))
                    } catch (_: android.content.ActivityNotFoundException) { // 没装邮件应用时显示邮箱地址
                        android.widget.Toast.makeText(requireContext(), CustomConstant.THREEBROOK_SUPPORT_EMAIL, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                addDestinationPreference(
                    R.string.about,
                    R.drawable.tb_ic_info,
                    R.id.action_settingsFragment_to_aboutFragment
                )
            }
        }
    }
}