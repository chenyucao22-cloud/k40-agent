package dev.channing.androidagent.local

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object Models {
    const val SIZE = 1117320736L
    const val HASH = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    private const val URL_PATH = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    fun file(context: Context) = File(context.filesDir, "qwen2.5-1.5b-q4_k_m.gguf")

    @Synchronized fun install(context: Context, imported: InputStream?, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val ready = file(context)
        val part = File(context.filesDir, if (imported == null) "model.download.part" else "model.import.part")
        if (imported != null) {
            try {
                part.outputStream().use { output ->
                    copy(imported, 0, cancelled, progress) { bytes, count -> output.write(bytes, 0, count) }
                }
            } catch (error: Exception) {
                part.delete()
                throw error
            }
        } else if (part.length() != SIZE) {
            val offset = part.length().takeIf { it < SIZE } ?: 0L
            val request = URL(URL_PATH).openConnection() as HttpURLConnection
            request.connectTimeout = 15_000
            request.readTimeout = 15_000
            request.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) request.setRequestProperty("Range", "bytes=$offset-")
            try {
                val start = when (request.responseCode) {
                    200 -> 0
                    206 -> {
                        require(request.getHeaderField("Content-Range") == "bytes $offset-${SIZE - 1}/$SIZE")
                        offset
                    }
                    else -> error("下载失败")
                }
                request.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { output ->
                        output.setLength(start)
                        output.seek(start)
                        copy(input, start, cancelled, progress) { bytes, count -> output.write(bytes, 0, count) }
                    }
                }
            } finally {
                request.disconnect()
            }
        }
        require(part.length() == SIZE) { "文件大小不符" }
        progress(100)
        val sha = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                check(!cancelled()) { "取消" }
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        if (sha.digest().joinToString("") { "%02x".format(it) } != HASH) {
            part.delete()
            error("模型校验失败")
        }
        check(!cancelled()) { "取消" }
        check(part.renameTo(ready)) { "模型保存失败" }
    }

    private fun copy(input: InputStream, offset: Long, cancelled: () -> Boolean, progress: (Int) -> Unit,
                     write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(128 * 1024)
        var total = offset
        var last = -1
        while (true) {
            check(!cancelled()) { "取消" }
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= SIZE)
            write(buffer, count)
            val percentage = (total * 100 / SIZE).toInt()
            if (last != percentage) { last = percentage; progress(percentage) }
        }
    }
}

object LocalNative {
    init { System.loadLibrary("agent_native") }
    external fun generate(path: ByteArray, input: ByteArray, threads: Int): ByteArray?
    external fun abort()
    external fun reset()
}
