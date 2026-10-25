package com.yuyan.imemodule.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 离线语音识别（sherpa-onnx + SenseVoice）：按住录音，松开识别
 * 模型放在 Android/data/<包名>/files/sensevoice/ 下（model.int8.onnx、tokens.txt）
 */
object VoiceInputManager {
    private const val TAG = "VoiceInput"
    private const val SAMPLE_RATE = 16000
    private const val MODEL_DIR = "sensevoice"
    private const val MODEL_FILE = "model.int8.onnx"
    private const val TOKENS_FILE = "tokens.txt"
    private const val MAX_RECORD_SECONDS = 60 // 单次最长录音
    private const val MIN_RECORD_SAMPLES = SAMPLE_RATE / 5 // 短于0.2秒视为误触
    private const val SILENCE_RMS = 0.01f // 最响的一帧也低于此音量视为没说话，模型对静音会凭空识别出字

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizerLock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var session: RecordSession? = null

    private class RecordSession(val record: AudioRecord) {
        @Volatile var active = true
        val chunks = ArrayList<FloatArray>()
        var job: Job? = null
    }

    val isRecording: Boolean get() = session != null

    fun modelDir(context: Context): File = File(context.getExternalFilesDir(null), MODEL_DIR)

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, MODEL_FILE).exists() && File(dir, TOKENS_FILE).exists()
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 提前加载模型，首次加载需要几秒，放在按下时调用，和用户说话同时进行
     */
    fun preload(context: Context) {
        scope.launch {
            try { getRecognizer(context) } catch (e: Throwable) { Log.e(TAG, "load model failed", e) } // 含原生库加载失败
        }
    }

    private suspend fun getRecognizer(context: Context): OfflineRecognizer = recognizerLock.withLock {
        recognizer ?: run {
            val dir = modelDir(context)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, MODEL_FILE).absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true, // 输出标点和阿拉伯数字
                    ),
                    tokens = File(dir, TOKENS_FILE).absolutePath,
                    numThreads = 2,
                ),
            )
            Log.i(TAG, "loading model from $dir")
            val start = SystemClock.elapsedRealtime()
            OfflineRecognizer(config = config).also {
                recognizer = it
                Log.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - start}ms")
            }
        }
    }

    @SuppressLint("MissingPermission") // 调用方已检查权限
    fun startRecording(): Boolean {
        if (session != null) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (e: Exception) {
            Log.e(TAG, "create AudioRecord failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val current = RecordSession(record)
        session = current
        record.startRecording()
        current.job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var total = 0
            while (current.active && total < SAMPLE_RATE * MAX_RECORD_SECONDS) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    current.chunks.add(FloatArray(read) { buffer[it] / 32768f })
                    total += read
                }
            }
        }
        return true
    }

    /**
     * 停止录音并识别，结果在主线程回调；没听到内容或识别失败时回调空字符串
     */
    fun stopAndRecognize(context: Context, onResult: (String) -> Unit) {
        val current = session ?: return onResult("")
        session = null
        current.active = false
        scope.launch {
            val audio = finishSession(current)
            val peakRms = peakFrameRms(audio)
            Log.i(TAG, "recorded ${audio.size / SAMPLE_RATE.toFloat()}s, peak rms=$peakRms")
            val text = if (audio.size < MIN_RECORD_SAMPLES || peakRms < SILENCE_RMS) "" else recognize(context, audio)
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }

    fun cancel() {
        val current = session ?: return
        session = null
        current.active = false
        scope.launch { finishSession(current) }
    }

    /**
     * 识别一段16kHz单声道音频
     */
    suspend fun recognize(context: Context, audio: FloatArray): String = try {
        val recognizer = getRecognizer(context)
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    } catch (e: Throwable) { // 含原生库加载失败，不能让输入法崩溃
        Log.e(TAG, "recognize failed", e)
        ""
    }

    /**
     * 按30毫秒分帧，返回最响一帧的均方根音量
     */
    private fun peakFrameRms(audio: FloatArray): Float {
        val frame = SAMPLE_RATE * 30 / 1000
        var peak = 0f
        var start = 0
        while (start + frame <= audio.size) {
            var sum = 0f
            for (i in start until start + frame) sum += audio[i] * audio[i]
            peak = maxOf(peak, kotlin.math.sqrt(sum / frame))
            start += frame
        }
        return peak
    }

    private suspend fun finishSession(current: RecordSession): FloatArray {
        current.job?.join()
        try { current.record.stop() } catch (_: Exception) { }
        current.record.release()
        val audio = FloatArray(current.chunks.sumOf { it.size })
        var offset = 0
        for (chunk in current.chunks) {
            chunk.copyInto(audio, offset)
            offset += chunk.size
        }
        return audio
    }
}
