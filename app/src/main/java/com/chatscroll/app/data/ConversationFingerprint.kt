package com.chatscroll.app.data

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Length-delimited, versioned fingerprints cover every message field, but no library metadata. */
internal object ConversationFingerprint {
    const val PREFIX = "v2:"
    const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
    val assetPattern = Regex("asset:([0-9a-f]{64})")

    fun hash(messages: List<ChatMessage>, imageBytes: ((String) -> ByteArray?)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            val bytes = value?.toByteArray(Charsets.UTF_8)
            val size = bytes?.size ?: -1
            digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            if (bytes != null) digest.update(bytes)
        }
        field("chatscroll-messages-v2")
        field(messages.size.toString())
        messages.forEach { message ->
            field(message.role)
            field(message.content)
            field(message.thinking)
            field(message.speaker)
            field(message.time)
            field(message.blocks.size.toString())
            message.blocks.forEach { block -> field(block.kind); field(block.name); field(block.text) }
            field(message.images.size.toString())
            message.images.forEach { image ->
                val bytes = imageBytes?.invoke(image) ?: when {
                    image.startsWith("data:", true) -> decodeImage(image)
                    image.startsWith("file:", true) -> readLocalAsset(image)
                    else -> null
                }
                if (bytes != null) { field("bytes"); field(sha256(bytes)) }
                else { field("reference"); field(image) }
            }
        }
        return PREFIX + digest.digest().toHex()
    }

    fun decodeImage(image: String): ByteArray {
        val comma = image.indexOf(',')
        if (comma < 0 || !Regex("data:image/[a-zA-Z0-9.+-]+;base64", RegexOption.IGNORE_CASE)
                .matches(image.substring(0, comma))) {
            throw ConversationStorageException("Unsupported embedded image format")
        }
        if (image.length.toLong() > MAX_IMAGE_BYTES * 2) throw ConversationStorageException("Image exceeds size limit")
        val encoded = image.substring(comma + 1).filterNot(Char::isWhitespace)
        val decoded = try { Base64.getDecoder().decode(encoded) }
        catch (error: IllegalArgumentException) { throw ConversationStorageException("Invalid image encoding", error) }
        if (decoded.size.toLong() > MAX_IMAGE_BYTES) throw ConversationStorageException("Image exceeds size limit")
        return decoded
    }

    private fun readLocalAsset(reference: String): ByteArray {
        val uri = try { URI(reference) } catch (error: java.net.URISyntaxException) {
            throw ConversationStorageException("Invalid local image reference", error)
        }
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) {
            throw ConversationStorageException("Invalid local image reference")
        }
        val file = File(uri).canonicalFile
        if (!Regex("[0-9a-f]{64}\\.blob").matches(file.name) ||
            file.parentFile?.name?.endsWith(".assets") != true) {
            throw ConversationStorageException("Image is not a repository asset")
        }
        val bytes = readImage(file)
        if (sha256(bytes) != file.name.removeSuffix(".blob")) throw ConversationStorageException("Image checksum mismatch")
        return bytes
    }

    fun readImage(file: File): ByteArray {
        if (!file.isFile || file.length() > MAX_IMAGE_BYTES) throw ConversationStorageException("Image is missing or too large")
        return file.readBytes()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
