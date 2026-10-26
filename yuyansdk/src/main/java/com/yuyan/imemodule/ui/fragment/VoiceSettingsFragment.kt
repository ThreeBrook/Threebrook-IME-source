package com.yuyan.imemodule.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.lifecycle.lifecycleScope
import com.yuyan.imemodule.R
import com.yuyan.imemodule.voice.VoiceInputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import java.io.File

/**
 * 语音识别设置：查看模型状态，从手机文件导入模型（不需要数据线，也不需要网络权限），删除模型
 */
class VoiceSettingsFragment : Fragment() {

    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var deleteButton: Button

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        status = TextView(context).apply { textSize = 16f }
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
                MODEL_URL, TOKENS_URL)
            setTextIsSelectable(true)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, dp(16), 0, 0)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(status)
            addView(importButton)
            addView(deleteButton)
            addView(help)
        }
        return ScrollView(context).apply { addView(content) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
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
        // 三溪自己的下载地址，与已测试的 SenseVoice int8 2024-07-17 模型一致；地址不带 App 版本号，换版本不用改
        private const val MODEL_URL = "https://dl.threebrook.com/ime/models/sensevoice-2024-07-17/model.int8.onnx"
        private const val TOKENS_URL = "https://dl.threebrook.com/ime/models/sensevoice-2024-07-17/tokens.txt"
    }
}
