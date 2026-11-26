package dev.channing.androidagent.local

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

object Speech {
    @SuppressLint("MissingPermission")
    fun listen(context: Context, cancelled: () -> Boolean, partial: (String) -> Unit): String {
        val recognizer = OnlineRecognizer(context.assets, OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig("speech/encoder.onnx", "speech/decoder.onnx", "speech/joiner.onnx"),
                tokens = "speech/tokens.txt", numThreads = 2, modelType = "zipformer"
            )
        ))
        try {
            val stream = recognizer.createStream()
            try {
                val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(size > 0)
                val record = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 3200))
                try {
                    require(record.state == AudioRecord.STATE_INITIALIZED)
                    if (cancelled()) return ""
                    record.startRecording()
                    require(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    val pcm = ShortArray(800)
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var latest = ""
                    while (!cancelled() && SystemClock.elapsedRealtime() < deadline) {
                        val count = record.read(pcm, 0, pcm.size)
                        check(count >= 0)
                        if (count == 0) continue
                        stream.acceptWaveform(FloatArray(count) { pcm[it] / 32768f }, 16000)
                        while (recognizer.isReady(stream) && !cancelled()) recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text
                        if (text != latest) { latest = text; partial(text) }
                        if (latest.isNotBlank() && recognizer.isEndpoint(stream)) break
                    }
                    if (cancelled()) return ""
                    stream.acceptWaveform(FloatArray(4800), 16000)
                    stream.inputFinished()
                    while (recognizer.isReady(stream) && !cancelled()) recognizer.decode(stream)
                    return recognizer.getResult(stream).text.trim()
                } finally {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    record.release()
                }
            } finally { stream.release() }
        } finally { recognizer.release() }
    }
}
