package com.chatscroll.app.parser

/** Format selection, role framing, and the common ordered message representation. */
object ChatMarkdownParser {
    const val VERSION = 2

    enum class Role { USER, ASSISTANT }

    data class Block(
        val kind: String,   // "thinking" | "tool" | "text"
        val text: String,
        val name: String? = null
    )

    data class Message(
        val role: Role,
        val content: String,
        val thinking: String? = null,
        val images: List<String> = emptyList(),
        val blocks: List<Block> = emptyList(),
        val speaker: String? = null,
        val time: String? = null
    )

    data class Result(val title: String?, val messages: List<Message>)

    private val titleRegex = Regex("^#\\s+(.+?)\\s*$")
    private val exportedRegex = Regex("^\\**\\s*Exported on .*$", RegexOption.IGNORE_CASE)
    private val roleRegex = Regex(
        "^ {0,3}\\*{0,2}\\s*(User|Assistant)\\s*\\*{0,2}\\s*:[ \\t]?(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val hrRegex = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val whitespaceRegex = Regex("\\s+")
    private val rawBase64Regex = Regex("[A-Za-z0-9+/]+={0,2}")
    private val supportedFormats = setOf("auto", "rikkahub", "chatbox", "kelivo", "markdown")

    /** Detection needs an opening export structure; examples later in a document cannot select a format. */
    fun detectFormat(content: String): String {
        val source = content.removePrefix("﻿")
        if (source.isBlank()) return "markdown"
        if (ChatboxParser.isChatbox(source)) return "chatbox"
        if (KelivoParser.isKelivo(source)) return "kelivo"
        val lines = source.lines()
        val first = firstContentLine(lines)
        val header = lines.getOrNull(first)?.let(::roleHeader)
        // Bare lowercase labels are common in notes and prompt tutorials. An override remains available.
        return if (header != null && (header.groupValues[1].any { it.isUpperCase() } ||
                    lines.take(first).any { exportedRegex.matches(it.trim()) })) "rikkahub" else "markdown"
    }

    fun parse(content: String, format: String = "auto"): Result {
        val requested = format.lowercase()
        require(requested in supportedFormats) { "Unsupported format: $format" }
        val source = content.removePrefix("﻿")
        if (source.isBlank()) return Result(null, emptyList())
        val selected = if (requested == "auto") detectFormat(source) else requested
        val result = when (selected) {
            "chatbox" -> if (ChatboxParser.hasOpeningHeader(source)) ChatboxParser.parse(source) else null
            "kelivo" -> if (KelivoParser.isKelivo(source)) KelivoParser.parse(source) else null
            "rikkahub" -> parseRoleHeaders(source)
            else -> null
        }
        // A mistaken override or an incomplete export must never turn a nonempty document into nothing.
        return result?.takeIf { it.messages.isNotEmpty() } ?: document(source)
    }

    internal fun document(source: String): Result = Result(
        title(source.lines()),
        listOf(message(Role.ASSISTANT, listOf(Block("text", trimBlankLines(source)))))
    )

    private fun title(lines: List<String>): String? = lines.take(firstContentLine(lines))
        .firstNotNullOfOrNull { titleRegex.matchEntire(it.trim())?.groupValues?.get(1) }

    private fun roleHeader(line: String): MatchResult? =
        if (line.startsWith("    ") || line.startsWith('\t')) null else roleRegex.matchEntire(line)

    private fun parseRoleHeaders(source: String): Result? {
        val lines = source.lines()
        val start = firstContentLine(lines)
        if (lines.getOrNull(start)?.let(::roleHeader) == null) return null
        val messages = ArrayList<Message>()
        val body = ArrayList<String>()
        var role: Role? = null
        var fence: MarkdownFenceScanner.Fence? = null

        fun flush() {
            val current = role ?: return
            val text = cleanBody(body)
            body.clear()
            if (text.isNotEmpty()) {
                val blocks = if (current == Role.ASSISTANT) RikkaHubParser.segment(text) else listOf(Block("text", text))
                messages.add(message(current, blocks))
            }
        }

        for (line in lines.drop(start)) {
            val active = fence
            if (active != null) {
                body.add(line)
                if (active.closes(line)) fence = null
                continue
            }
            val opening = MarkdownFenceScanner.opening(line)
            if (opening != null) {
                body.add(line)
                fence = opening
                continue
            }
            val header = roleHeader(line)
            if (header != null) {
                flush()
                role = if (header.groupValues[1].equals("User", true)) Role.USER else Role.ASSISTANT
                val inline = header.groupValues[2]
                if (inline.isNotBlank()) {
                    body.add(inline)
                    fence = MarkdownFenceScanner.opening(inline)
                }
            } else {
                body.add(line)
            }
        }
        flush()
        return Result(title(lines), messages)
    }

    /** Only an export preamble may precede the first turn, never arbitrary body text. */
    internal fun firstContentLine(lines: List<String>): Int {
        for (index in lines.indices) {
            val raw = lines[index]
            val line = raw.trim()
            if (line.isBlank()) continue
            if (!raw.startsWith("    ") && !raw.startsWith('\t') &&
                (line.startsWith("# ") || line.startsWith("## ") ||
                    exportedRegex.matches(line) || hrRegex.matches(line))) continue
            return index
        }
        return lines.size
    }

    /** Normalize images in the blocks actually displayed, then derive searchable text. */
    internal fun message(
        role: Role,
        blocks: List<Block>,
        speaker: String? = null,
        time: String? = null
    ): Message {
        val images = ArrayList<String>()
        val finalized = blocks.map { it.copy(text = extractImages(it.text, images)) }
        return Message(
            role = role,
            content = finalized.filter { it.kind == "text" }.joinToString("\n\n") { it.text },
            images = images,
            blocks = finalized,
            speaker = speaker,
            time = time
        )
    }

    private fun extractImages(text: String, images: MutableList<String>): String {
        if (!text.contains("![")) return text
        val result = StringBuilder()
        var copied = 0
        for (image in MarkdownImageScanner.scan(text)) {
            val normalized = embeddedImage(image.destination) ?: continue
            result.append(text, copied, image.start)
            images.add(normalized)
            result.append("\n\n[image:${images.size - 1}]\n\n")
            copied = image.end
        }
        if (copied == 0) return text
        result.append(text, copied, text.length)
        return trimBlankLines(result.toString())
    }

    private fun embeddedImage(destination: String): String? {
        if (destination.startsWith("data:image/", true)) {
            val comma = destination.indexOf(',')
            if (comma < 0 || !destination.substring(0, comma).endsWith(";base64", true)) return null
            val data = whitespaceRegex.replace(destination.substring(comma + 1), "")
            if (!rawBase64Regex.matches(data)) return null
            return destination.substring(0, comma + 1) + data
        }
        val data = whitespaceRegex.replace(destination, "")
        return if (data.length >= 100 && rawBase64Regex.matches(data)) "data:${sniffMime(data)};base64,$data" else null
    }

    private fun sniffMime(base64: String): String = when {
        base64.startsWith("iVBOR") -> "image/png"
        base64.startsWith("/9j/") -> "image/jpeg"
        base64.startsWith("R0lGOD") -> "image/gif"
        base64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }

    /** Remove empty boundary lines without changing code/list indentation or Markdown hard breaks. */
    internal fun trimBlankLines(text: String): String = trimBlankLines(text.lines())

    internal fun trimBlankLines(lines: List<String>): String {
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isBlank()) start++
        while (end > start && lines[end - 1].isBlank()) end--
        return lines.subList(start, end).joinToString("\n")
    }

    /** Strip trailing export separators only outside a code fence. */
    internal fun cleanBody(lines: List<String>): String {
        var fence: MarkdownFenceScanner.Fence? = null
        val separators = HashSet<Int>()
        for (index in lines.indices) {
            val active = fence
            if (active != null) {
                if (active.closes(lines[index])) fence = null
            } else {
                fence = MarkdownFenceScanner.opening(lines[index])
                if (fence == null && hrRegex.matches(lines[index]) &&
                    !lines[index].startsWith("    ") && !lines[index].startsWith('\t') &&
                    (index == 0 || lines[index - 1].isBlank()) &&
                    (index == lines.lastIndex || lines[index + 1].isBlank())) separators.add(index)
            }
        }
        var end = lines.size
        while (end > 0 && (lines[end - 1].isBlank() || end - 1 in separators)) end--
        return trimBlankLines(lines.subList(0, end))
    }
}
