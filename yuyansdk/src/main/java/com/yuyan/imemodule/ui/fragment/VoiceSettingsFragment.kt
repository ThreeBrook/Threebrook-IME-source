package com.yuyan.imemodule.ui.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yuyan.imemodule.R
import com.yuyan.imemodule.voice.ModelDownloader
import com.yuyan.imemodule.voice.VoiceInputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import java.io.File

/**
 * 语音识别设置：查看模型状态，一键下载模型（下载前说明要联网），或从手机文件导入模型，删除模型
 */
class VoiceSettingsFragment : Fragment() {

    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var importButton: Button
    private lateinit var deleteButton: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        status = TextView(context).apply { textSize = 16f }
        downloadButton = Button(context).apply {
            setOnClickListener { if (ModelDownloader.isRunning) ModelDownloader.cancel() else confirmDownload() }
        }
        importButton = Button(context).apply {
            setText(R.string.voice_model_import)
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }
        deleteButton = Button(context).apply {
            setText(R.string.voice_model_delete)
            setOnClickListener { confirmDelete() }
        }
        val help = TextView(context).apply {
            text = getString(R.string.voice_model_help, VoiceInputManager.MODEL_FILE, VoiceInputManager.TOKENS_FILE,
                MODEL_URL, TOKENS_URL, ModelDownloader.HOST)
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, dp(16), 0, 0)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status)
            addView(downloadButton)
            addView(importButton)
            addView(deleteButton)
            addView(help)
        }
        return ScrollView(context).apply { addView(content) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloader.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ModelDownloader.State) {
        val running = state is ModelDownloader.State.Running || state is ModelDownloader.State.Verifying
        downloadButton.setText(if (running) R.string.voice_model_download_cancel else R.string.voice_model_download)
        importButton.isEnabled = !running
        when (state) {
            is ModelDownloader.State.Running -> {
                status.text = getString(R.string.voice_model_downloading, state.downloaded * 100 / state.total,
                    state.downloaded / 1024 / 1024, state.total / 1024 / 1024)
                deleteButton.isEnabled = false
            }
            is ModelDownloader.State.Verifying -> {
                status.setText(R.string.voice_model_verifying)
                deleteButton.isEnabled = false
            }
            is ModelDownloader.State.Done -> {
                ModelDownloader.acknowledge()
                refresh()
                showDownloaded()
            }
            is ModelDownloader.State.Failed -> {
                ModelDownloader.acknowledge()
                refresh()
                showFailed(state.reason)
            }
            ModelDownloader.State.Idle -> refresh()
        }
    }

    /**
     * 下载前说清楚：要联网、下多大、连哪里、下完可以关掉联网
     */
    private fun confirmDownload() {
        val context = requireContext()
        var message = getString(R.string.voice_model_download_notice, ModelDownloader.TOTAL_BYTES / 1024 / 1024, ModelDownloader.HOST)
        if (ModelDownloader.isMetered(context)) message = getString(R.string.voice_model_download_metered) + "\n\n" + message
        AlertDialog.Builder(context)
            .setTitle(R.string.voice_model_download_title)
            .setMessage(message)
            .setPositiveButton(R.string.voice_model_download_start) { _, _ -> ModelDownloader.start(context) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloaded() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.voice_model_downloaded_title)
            .setMessage(R.string.voice_model_downloaded)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.voice_model_open_app_settings) { _, _ -> openAppSettings() }
            .show()
    }

    private fun showFailed(reason: ModelDownloader.Reason) {
        val message = when (reason) {
            ModelDownloader.Reason.NETWORK -> R.string.voice_model_download_failed_network
            ModelDownloader.Reason.VERIFY -> R.string.voice_model_download_failed_verify
            ModelDownloader.Reason.STORAGE -> R.string.voice_model_download_failed_storage
        }
        AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(R.string.voice_model_download_retry) { _, _ -> ModelDownloader.start(requireContext()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 打开系统里本应用的详情页，部分手机可以在这里关掉联网（流量管理/联网控制） */
    private fun openAppSettings() {
        val context = requireContext()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (ModelDownloader.isRunning) return // 下载进度由 render 显示
        val context = requireContext()
        val dir = VoiceInputManager.modelDir(context)
        val model = File(dir, VoiceInputManager.MODEL_FILE)
        val tokens = File(dir, VoiceInputManager.TOKENS_FILE)
        status.text = when {
            VoiceInputManager.isModelReady(context) -> getString(R.string.voice_model_ready, model.length() / 1024 / 1024)
            model.exists() -> getString(R.string.voice_model_missing_file, VoiceInputManager.TOKENS_FILE)
            tokens.exists() -> getString(R.string.voice_model_missing_file, VoiceInputManager.MODEL_FILE)
            else -> getString(R.string.voice_model_not_installed)
        }
        deleteButton.isEnabled = model.exists() || tokens.exists()
        downloadButton.isEnabled = !VoiceInputManager.isModelReady(context)
    }

    private fun importFiles(uris: List<Uri>) {
        val context = requireContext()
        importButton.isEnabled = false
        deleteButton.isEnabled = false
        lifecycleScope.launch {
            val messages = mutableListOf<String>()
            VoiceInputManager.release() // 正在使用的模型文件要被替换
            for (uri in uris) {
                val (name, size) = queryNameAndSize(uri)
                val target = when {
                    name.endsWith(".onnx", ignoreCase = true) -> VoiceInputManager.MODEL_FILE
                    name.startsWith("tokens", ignoreCase = true) && name.endsWith(".txt", ignoreCase = true) -> VoiceInputManager.TOKENS_FILE
                    else -> null
                }
                when {
                    target == null -> messages += getString(R.string.voice_model_skip_unknown, name)
                    target == VoiceInputManager.MODEL_FILE && size in 0 until MIN_MODEL_BYTES ->
                        messages += getString(R.string.voice_model_skip_small, name)
                    else -> {
                        val ok = copyTo(uri, File(VoiceInputManager.modelDir(context), target), size)
                        messages += getString(if (ok) R.string.voice_model_imported else R.string.voice_model_import_failed, name)
                    }
                }
            }
            importButton.isEnabled = true
            refresh()
            AlertDialog.Builder(context).setMessage(messages.joinToString("\n")).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: ""
                val size = if (cursor.isNull(1)) -1L else cursor.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "") to -1L
    }

    /**
     * 先写临时文件再改名，导入中途失败不会留下半个模型
     */
    private suspend fun copyTo(uri: Uri, target: File, size: Long): Boolean = withContext(Dispatchers.IO) {
        val resolver = requireContext().contentResolver
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.importing")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastShown = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastShown >= 8 shl 20) {
                            lastShown = copied
                            val text = if (size > 0) getString(R.string.voice_model_importing_percent, target.name, copied * 100 / size)
                                else getString(R.string.voice_model_importing_mb, target.name, copied / 1024 / 1024)
                            withContext(Dispatchers.Main) { status.text = text }
                        }
                    }
                }
            } ?: return@withContext false
            target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.voice_model_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    VoiceInputManager.release()
                    val dir = VoiceInputManager.modelDir(requireContext())
                    File(dir, VoiceInputManager.MODEL_FILE).delete()
                    File(dir, VoiceInputManager.TOKENS_FILE).delete()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MIN_MODEL_BYTES = 50L shl 20 // 小于50MB的.onnx不是完整的SenseVoice模型
        // 手动下载用的地址，和自动下载是同一份文件（见 ModelDownloader）
        private const val MODEL_URL = "https://dl.threebrook.com/ime/models/sensevoice-2024-07-17/model.int8.onnx"
        private const val TOKENS_URL = "https://dl.threebrook.com/ime/models/sensevoice-2024-07-17/tokens.txt"
    }
}
