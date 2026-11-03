package com.yuyan.imemodule.voice

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 从三溪的下载服务器下载语音模型。整个 App 只有这里联网：只在用户点"下载"后连接 dl.threebrook.com，只下载不上传。
 * 下载到 .downloading 临时文件，断了再点下载接着下；下完核对大小和 SHA-256 再改名，不会留下坏模型。
 * 放在进程级的协程里，离开设置页面也继续下载
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    const val HOST = "dl.threebrook.com"
    private const val BASE_URL = "https://$HOST/ime/models/sensevoice-2024-07-17/"

    // 与已测试的 SenseVoice int8 2024-07-17 模型一致（大小、哈希已核对）；地址不带 App 版本号，换版本不用改
    private class Remote(val name: String, val size: Long, val sha256: String)

    private val FILES = listOf(
        Remote(VoiceInputManager.TOKENS_FILE, 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        Remote(VoiceInputManager.MODEL_FILE, 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }

    sealed class State {
        object Idle : State()
        class Running(val downloaded: Long, val total: Long) : State()
        object Verifying : State()
        object Done : State()
        class Failed(val reason: Reason) : State()
    }

    enum class Reason { NETWORK, VERIFY, STORAGE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val isRunning: Boolean get() = job?.isActive == true

    /** 当前网络是否按流量计费（移动数据、热点），下载前额外提醒 */
    fun isMetered(context: Context): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    fun start(context: Context) {
        if (isRunning) return
        val dir = VoiceInputManager.modelDir(context.applicationContext)
        job = scope.launch {
            _state.value = State.Running(alreadyDownloaded(dir), TOTAL_BYTES)
            _state.value = try {
                VoiceInputManager.release() // 正在使用的模型文件要被替换
                download(dir)
                State.Done
            } catch (e: VerifyException) {
                Log.e(TAG, "verify failed", e)
                State.Failed(Reason.VERIFY)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "download failed", e)
                State.Failed(if (dir.usableSpace < 32L shl 20) Reason.STORAGE else Reason.NETWORK)
            } catch (e: kotlinx.coroutines.CancellationException) {
                State.Idle
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 失败或完成的提示看过之后回到空闲状态 */
    fun acknowledge() {
        if (!isRunning) _state.value = State.Idle
    }

    private fun alreadyDownloaded(dir: File): Long = FILES.sumOf { remote ->
        val target = File(dir, remote.name)
        if (target.length() == remote.size) remote.size else File(dir, "${remote.name}.downloading").length()
    }

    private suspend fun download(dir: File) {
        dir.mkdirs()
        var done = 0L
        for (remote in FILES) {
            val target = File(dir, remote.name)
            if (target.length() == remote.size && sha256(target) == remote.sha256) {
                done += remote.size
                continue
            }
            val tmp = File(dir, "${remote.name}.downloading")
            if (tmp.length() > remote.size) tmp.delete()
            fetch(remote, tmp, done)
            _state.value = State.Verifying
            if (tmp.length() != remote.size || sha256(tmp) != remote.sha256) {
                tmp.delete() // 内容不对，下次从头下
                throw VerifyException(remote.name)
            }
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("rename ${remote.name}")
            done += remote.size
        }
    }

    /** 从临时文件已有的长度接着下载（Range 请求） */
    private suspend fun fetch(remote: Remote, tmp: File, before: Long) {
        var have = tmp.length()
        if (have == remote.size) return
        val conn = URL(BASE_URL + remote.name).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false // 只从三溪的服务器下载，不跟随跳转
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // 服务器不支持续传，从头下
                else -> throw java.io.IOException("HTTP $code")
            }
            if (!append) have = 0
            conn.inputStream.use { input ->
                FileOutputStream(tmp, append).use { output ->
                    val buffer = ByteArray(256 shl 10)
                    var lastShown = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        have += read
                        if (have - lastShown >= 1 shl 20 || have == remote.size) {
                            lastShown = have
                            _state.value = State.Running(before + have, TOTAL_BYTES)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class VerifyException(name: String) : Exception(name)
}
