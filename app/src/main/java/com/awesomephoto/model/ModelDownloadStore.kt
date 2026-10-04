package com.awesomephoto.model

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Persistent, atomic model storage. No downloaded model is removed when another is selected. */
class ModelDownloadStore(private val directory: File) {
    fun has(model: DownloadableModel) = File(directory, model.fileName).let { it.isFile && it.length() == model.bytes }

    fun prepare(model: DownloadableModel, cancelled: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        directory.mkdirs()
        val destination = File(directory, model.fileName)
        if (has(model) && digest(destination, cancelled) == model.sha256) return destination
        directory.listFiles { file -> file.name.startsWith(model.id + "-") && file.name.endsWith(".part") }?.forEach { it.delete() }
        check(directory.usableSpace >= model.bytes + 1024 * 1024) { "存储空间不足，无法下载模型" }
        val temporary = File.createTempFile(model.id + "-", ".part", directory)
        try {
            var url = URL(model.url)
            var redirects = 0
            while (true) {
                cancelled()
                check(url.protocol == "https") { "模型下载必须使用 HTTPS" }
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        check(++redirects <= 5) { "模型下载重定向过多" }
                        url = URL(url, connection.getHeaderField("Location") ?: error("模型下载地址无效"))
                        continue
                    }
                    check(status == 200) { "模型下载失败（HTTP $status），请稍后重试" }
                    connection.inputStream.use { input -> verifyAndWrite(input, temporary, model, cancelled, progress) }
                    break
                } finally { connection.disconnect() }
            }
            cancelled()
            check(temporary.renameTo(destination)) { "无法保存模型，请检查存储空间" }
            return destination
        } finally { temporary.delete() }
    }

    internal fun verifyAndWrite(input: InputStream, file: File, model: DownloadableModel,
        cancelled: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val hash = MessageDigest.getInstance("SHA-256")
        var received = 0L
        val buffer = ByteArray(64 * 1024)
        file.outputStream().use { output ->
            while (true) {
                cancelled()
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                check(received <= model.bytes) { "模型文件长度不符" }
                hash.update(buffer, 0, count); output.write(buffer, 0, count)
                progress(received, model.bytes)
            }
            output.fd.sync()
        }
        check(received == model.bytes && hex(hash.digest()) == model.sha256) { "模型校验失败，请重新下载" }
    }

    private fun digest(file: File, cancelled: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancelled()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
