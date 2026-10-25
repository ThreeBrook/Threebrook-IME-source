package com.yuyan.imemodule.ui.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.utils.TimeUtils
import com.yuyan.imemodule.utils.addCategory
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.view.widget.withLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AboutFragment : PreferenceFragmentCompat() {
    private var exportTimestamp = System.currentTimeMillis()
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.withLoadingDialog(requireContext()) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                        export(outputStream).getOrThrow()
                    }
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream) = runCatching {
        val outputDir = File(Launcher.instance.context.filesDir, "crash_logs")
        ZipOutputStream(dest.buffered()).use { zipStream ->
            writeFileTree(outputDir, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun writeFileTree(srcDir: File, dest: ZipOutputStream) {
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.ime_yuyan_name) {
                isIconSpaceReserved = false
                addPreference(R.string.about_product, getString(R.string.about_product_summary, BuildConfig.versionName))
                addPreference(R.string.about_publisher, R.string.about_publisher_name)
                addPreference(R.string.about_website, CustomConstant.THREEBROOK_WEBSITE.removePrefix("https://")) {
                    openUri(CustomConstant.THREEBROOK_WEBSITE)
                }
                addPreference(R.string.about_contact, CustomConstant.THREEBROOK_SUPPORT_EMAIL) {
                    openUri("mailto:${CustomConstant.THREEBROOK_SUPPORT_EMAIL}")
                }
                addPreference(R.string.privacy_policy) { openLegal(LegalFragment.PAGE_PRIVACY, R.string.privacy_policy) }
                addPreference(R.string.about_eula) { openLegal(LegalFragment.PAGE_EULA, R.string.about_eula) }
            }
            // 用到的开源软件与数据：BSD 要求随二进制附上版权声明，CC BY-SA 要求署名
            addCategory(R.string.about_open_source) {
                isIconSpaceReserved = false
                addPreference(R.string.about_licenses, R.string.about_licenses_summary) {
                    openLegal(LegalFragment.PAGE_LICENSES, R.string.about_licenses)
                }
                addPreference(R.string.about_os_yuyan, R.string.about_os_yuyan_summary) { openUri(CustomConstant.YUYAN_IME_REPO) }
                addPreference(R.string.about_os_rime, R.string.about_os_rime_summary) { openUri("https://github.com/rime/librime") }
                addPreference(R.string.about_os_sherpa, R.string.about_os_sherpa_summary) { openUri("https://github.com/k2-fsa/sherpa-onnx") }
                addPreference(R.string.about_os_sensevoice, R.string.about_os_sensevoice_summary) { openUri("https://github.com/FunAudioLLM/SenseVoice") }
                addPreference(R.string.about_os_words, R.string.about_os_words_summary) { openUri("https://github.com/hermitdave/FrequencyWords") }
            }
            addCategory(R.string.app_version) {
                isIconSpaceReserved = false
                addPreference(R.string.version, BuildConfig.versionName)
                addPreference(R.string.build_git_hash, BuildConfig.AppCommitHead)
                addPreference(R.string.build_time, BuildConfig.AppBuildTime)

            }

            addCategory(R.string.app_log) {
                isIconSpaceReserved = false
                addPreference(R.string.export_crash_log) {
                    lifecycleScope.launch {
                        exportTimestamp = System.currentTimeMillis()
                        exportLauncher.launch("threebrook_input_crash_log${TimeUtils.iso8601UTCDateTime(exportTimestamp)}.zip")
                    }
                }
                addPreference(getString(R.string.about_copyright))
            }
        }


    }

    private fun openLegal(page: String, title: Int) {
        findNavController().navigate(R.id.action_aboutFragment_to_legalFragment, LegalFragment.args(page, getString(title)))
    }

    private fun openUri(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), uri, Toast.LENGTH_LONG).show() // 没有浏览器或邮件应用时至少把地址显示出来
        }
    }
}


