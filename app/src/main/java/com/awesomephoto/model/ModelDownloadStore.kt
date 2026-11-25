package com.awesomephoto.model

import com.awesomephoto.LocalizedFailure
import com.awesomephoto.R
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
        if (directory.usableSpace < model.bytes + 1024 * 1024) throw LocalizedFailure(R.string.model_insufficient_storage)
        val temporary = File.createTempFile(model.id + "-", ".part", directory)
        try {
            var url = URL(model.url)
            var redirects = 0
            while (true) {
                cancelled()
                if (url.protocol != "https") throw LocalizedFailure(R.string.model_requires_https)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (++redirects > 5) throw LocalizedFailure(R.string.model_redirect_limit)
                        url = URL(url, connection.getHeaderField("Location") ?: throw LocalizedFailure(R.string.model_invalid_url))
                        continue
                    }
                    if (status != 200) throw LocalizedFailure(R.string.model_http_failed, status)
                    connection.inputStream.use { input -> verifyAndWrite(input, temporary, model, cancelled, progress) }
                    break
                } finally { connection.disconnect() }
            }
            cancelled()
            if (!temporary.renameTo(destination)) throw LocalizedFailure(R.string.model_save_failed)
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
                if (received > model.bytes) throw LocalizedFailure(R.string.model_size_mismatch)
                hash.update(buffer, 0, count); output.write(buffer, 0, count)
                progress(received, model.bytes)
            }
            output.fd.sync()
        }
        if (received != model.bytes || hex(hash.digest()) != model.sha256) throw LocalizedFailure(R.string.model_verification_failed)
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
