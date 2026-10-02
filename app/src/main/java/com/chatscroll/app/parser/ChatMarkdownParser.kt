package com.chatscroll.app.parser

/**
 * Parser for chat transcripts exported as Markdown.
 *
 * Recognized layout (used by several AI chat apps when exporting):
 *
 *     # Conversation title
 *     *Exported on 2026-10-01 21:00:00*
 *
 *     **User**:
 *
 *     message text
 *
 *     ---
 *
 *     **Assistant**:
 *
 *     > reasoning line
 *     reply text
 *
 *     ---
 *
 * Tolerances:
 *  - role headers may be bold (`**User**:`) or plain (`User:`), and may carry
 *    inline content (`User: hello`);
 *  - trailing horizontal rules of each block (export separators) are removed,
 *    while thematic breaks inside a message body are preserved;
 *  - every role header starts a new message, even when it repeats the
 *    previous role (a compressed export keeps its recap separate from the
 *    first real user message);
 *  - a leading blockquote in an assistant message is extracted as its
 *    "thinking" (chain-of-thought) section;
 *  - embedded base64 images are normalized (whitespace stripped, missing
 *    data: prefix restored) so that wrapped exports still render;
 *  - if no role header is found at all, the whole file is kept as a single
 *    document message so that any Markdown file stays readable.
 */
object ChatMarkdownParser {

    enum class Role { USER, ASSISTANT }

    /** One collapsible block: a reasoning passage or a tool call. */
    data class Block(
        val kind: String,   // "thinking" | "tool"
        val text: String,
        val name: String? = null   // tool name, when kind == "tool"
    )

    data class Message(
        val role: Role,
        val content: String,
        val thinking: String? = null,
        // Embedded images pulled out of the text, in the order they appeared.
        // Keeping their (often huge) payloads out of the text means the reading
        // view never has to lay out hundreds of thousands of characters.
        val images: List<String> = emptyList(),
        // Reasoning and tool calls in the order they happened, so a reply that
        // thinks, calls a tool, then thinks again keeps that sequence.
        val blocks: List<Block> = emptyList()
    )

    data class Result(val title: String?, val messages: List<Message>)

    private val titleRegex = Regex("^#\\s+(.+?)\\s*$")
    private val exportedRegex = Regex("^\\s*\\**\\s*Exported on .*$", RegexOption.IGNORE_CASE)
    private val roleRegex =
        Regex("^\\s*\\*{0,2}\\s*(User|Assistant)\\s*\\*{0,2}\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val hrRegex = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    // No ^ or $ anchors: Kotlin's regex treats those as line boundaries by default,
    // which would make this match inside a larger block instead of a whole line.
    private val toolHeaderRegex = Regex("\\*\\*Tool\\*\\*:\\s*`([^`]*)`")

    // `![alt](url)` — url may contain almost anything except ')'.
    // DOT_MATCHES_ALL also disables UNIX_LINES, without which a negated set
    // such as [^)] stops at a newline and wrapped base64 would never match.
    private val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
    private val whitespaceRegex = Regex("\\s+")
    private val rawBase64Regex = Regex("[A-Za-z0-9+/=]+")

    fun parse(content: String): Result {
        var title: String? = null
        var role: Role? = null
        val buffer = StringBuilder()
        val rawMessages = ArrayList<Triple<Role, String, String?>>() // role, body, thinking

        fun flush() {
            val currentRole = role ?: return
            val body = cleanBody(buffer.toString())
            if (body.isNotEmpty()) {
                rawMessages.add(Triple(currentRole, body, null))
            }
            buffer.setLength(0)
        }

        for (raw in content.lines()) {
            if (role == null) {
                // --- preamble: title / export metadata / separators ---
                if (title == null) {
                    titleRegex.matchEntire(raw)?.let {
                        title = it.groupValues[1].trim()
                        continue
                    }
                }
                if (raw.isBlank() || exportedRegex.matchEntire(raw) != null ||
                    hrRegex.matchEntire(raw) != null
                ) {
                    continue
                }
                val headerMatch = roleRegex.matchEntire(raw)
                if (headerMatch != null) {
                    role = roleOf(headerMatch)
                    appendInline(headerMatch, buffer)
                }
                // any other preamble text is ignored
                continue
            }

            // --- inside a message body ---
            val headerMatch = roleRegex.matchEntire(raw)
            if (headerMatch != null) {
                flush()
                role = roleOf(headerMatch)
                appendInline(headerMatch, buffer)
            } else {
                buffer.append(raw).append('\n')
            }
        }
        flush()

        // Fallback: no role headers at all -> keep the file readable as one document
        if (rawMessages.isEmpty() && content.isNotBlank()) {
            rawMessages.add(Triple(Role.ASSISTANT, content.trim(), null))
        }

        val messages = rawMessages.map { (msgRole, body, _) ->
            if (msgRole == Role.ASSISTANT) {
                val (content, blocks) = segmentAssistant(body)
                val (text, images) = splitImages(normalizeImages(content))
                Message(msgRole, text, null, images, blocks)
            } else {
                val (text, images) = splitImages(normalizeImages(body))
                Message(msgRole, text, null, images)
            }
        }

        return Result(title, messages)
    }

    /**
     * Splits an assistant message into collapsible blocks and the visible reply.
     *
     * Reasoning is exported as `>` quote lines and a tool call starts at a
     * `**Tool**: \`name\`` line. Both can appear more than once and in any
     * order, so each run becomes its own block. A quote only counts as reasoning
     * when it is a run of quote lines with nothing but blank lines around it;
     * a quote used for emphasis inside the reply stays in the text.
     */
    private fun segmentAssistant(content: String): Pair<String, List<Block>> {
        val lines = content.lines()
        val blocks = ArrayList<Block>()
        val prose = ArrayList<String>()
        var index = 0

        while (index < lines.size) {
            if (lines[index].isBlank()) {
                prose.add(lines[index])
                index++
                continue
            }

            val quoteRun = readQuoteRun(lines, index)
            if (quoteRun != null) {
                blocks.add(Block("thinking", quoteRun.second))
                prose.add("")
                index = quoteRun.first
                continue
            }

            val toolName = toolNameOf(lines[index])
            if (toolName != null) {
                val start = index
                index++
                while (index < lines.size && !isBoundary(lines, index)) index++
                val detail = lines.subList(start + 1, index).joinToString("\n").trim()
                blocks.add(Block("tool", detail, toolName.ifBlank { null }))
                prose.add("")
                continue
            }

            prose.add(lines[index])
            index++
        }

        return prose.joinToString("\n").trim() to blocks
    }

    /**
     * A run of `>` lines counts as reasoning when it stands apart from the reply:
     * either it opens the message, or it closes it, or it sits between other
     * reasoning and tool calls. A quoted line used for emphasis inside the reply
     * has ordinary text on both sides and is left where it is.
     */
    private fun readQuoteRun(lines: List<String>, start: Int): Pair<Int, String>? {
        if (!lines[start].trimStart().startsWith(">")) return null

        var index = start
        val collected = ArrayList<String>()
        while (index < lines.size && lines[index].trimStart().startsWith(">")) {
            val trimmed = lines[index].trimStart()
            collected.add(if (trimmed.length > 1) trimmed.substring(1).trimStart() else "")
            index++
        }

        val atStart = previousReal(lines, start) == null
        val atEnd = nextReal(lines, index) == null
        // A quote directly after a tool call is the model's next thought, even
        // though the tool's own output (not its header) is what precedes it.
        val afterTool = toolHeaderRegex.containsMatchIn(regionBefore(lines, start))
        if (!atStart && !atEnd && !afterTool) return null

        val text = collected.joinToString("\n").trim()
        if (text.isEmpty()) return null
        return index to text
    }

    /** The text between the previous tool header and [index], or empty when there is none. */
    private fun regionBefore(lines: List<String>, index: Int): String {
        var cursor = index - 1
        while (cursor >= 0 && toolNameOf(lines[cursor]) == null) cursor--
        if (cursor < 0) return ""
        return lines.subList(cursor, index).joinToString("\n")
    }

    /** The nearest non-blank line before [index], or null at the message start. */
    private fun previousReal(lines: List<String>, index: Int): String? {
        var cursor = index - 1
        while (cursor >= 0 && lines[cursor].isBlank()) cursor--
        return if (cursor < 0) null else lines[cursor]
    }

    /** The nearest non-blank line at or after [index], or null at the message end. */
    private fun nextReal(lines: List<String>, index: Int): String? {
        var cursor = index
        while (cursor < lines.size && lines[cursor].isBlank()) cursor++
        return if (cursor >= lines.size) null else lines[cursor]
    }

    /** A line that belongs to the reasoning/tool region rather than the reply. */
    private fun isBlockLine(line: String?): Boolean {
        line ?: return false
        val trimmed = line.trim()
        return trimmed.startsWith(">") || toolNameOf(line) != null
    }

    /**
     * True when a tool call should stop here: the next tool starts, or a reasoning
     * passage starts. Everything else belongs to the tool, however long the line is.
     * A tool's output can be a single JSON line thousands of characters long, and
     * judging that by its first character used to split it out into the reply.
     */
    private fun isBoundary(lines: List<String>, index: Int): Boolean {
        val trimmed = lines[index].trim()
        if (trimmed.isEmpty()) return false
        if (toolNameOf(lines[index]) != null) return true
        return trimmed.startsWith(">")
    }

    /** The tool name when [line] is a whole-line tool header, otherwise null. */
    private fun toolNameOf(line: String): String? {
        val trimmed = line.trim()
        val match = toolHeaderRegex.matchEntire(trimmed) ?: return null
        return match.groupValues[1]
    }

    /**
     * Normalizes embedded images so wrapped / prefix-less base64 payloads
     * become valid, single-line `data:` URIs that the renderer can show.
     */
    private fun normalizeImages(content: String): String {
        if (!content.contains("![")) return content
        return imageRegex.replace(content) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2].trim()
            val fixed = fixImageUrl(url)
            if (fixed != null) "![$alt]($fixed)" else match.value
        }
    }

    /**
     * Pulls embedded images out of the text, leaving a short placeholder where
     * each one was. The returned list holds the image destinations in order, so
     * the reading view can show them without ever laying out the raw payload.
     * Regular http(s) images are left untouched in the text.
     */
    private fun splitImages(content: String): Pair<String, List<String>> {
        if (!content.contains("![")) return content to emptyList()
        val images = ArrayList<String>()
        val text = imageRegex.replace(content) { match ->
            val url = match.groupValues[2].trim()
            if (url.startsWith("data:", ignoreCase = true)) {
                images.add(url)
                "\n\n[image:${images.size - 1}]\n\n"
            } else {
                match.value
            }
        }
        return text.trim() to images
    }

    /** Returns a normalized URL, or null when no change is needed. */
    private fun fixImageUrl(url: String): String? {
        if (url.isEmpty()) return null
        return when {
            url.startsWith("data:", ignoreCase = true) -> {
                val data = url.substringAfter(',', "")
                if (data.any { it.isWhitespace() }) {
                    url.substringBefore(',') + "," + whitespaceRegex.replace(data, "")
                } else {
                    null // already clean
                }
            }
            isRawBase64(url) -> "data:${sniffMime(url)};base64,${whitespaceRegex.replace(url, "")}"
            else -> {
                // http(s)/file links: only fix stray whitespace inside
                if (url.any { it.isWhitespace() }) whitespaceRegex.replace(url, "") else null
            }
        }
    }

    /** Long strings of pure base64 characters (with or without wrapping) are treated as raw base64. */
    private fun isRawBase64(url: String): Boolean {
        val cleaned = whitespaceRegex.replace(url, "")
        return cleaned.length >= 100 && rawBase64Regex.matches(cleaned)
    }

    private fun sniffMime(base64: String): String = when {
        base64.startsWith("iVBOR") -> "image/png"
        base64.startsWith("/9j/") -> "image/jpeg"
        base64.startsWith("R0lGOD") -> "image/gif"
        base64.startsWith("UklGR") -> "image/webp"
        else -> "image/png"
    }

    private fun roleOf(match: MatchResult): Role =
        if (match.groupValues[1].equals("User", ignoreCase = true)) Role.USER else Role.ASSISTANT

    private fun appendInline(match: MatchResult, buffer: StringBuilder) {
        val rest = match.groupValues[2].trim()
        if (rest.isNotEmpty()) buffer.append(rest).append('\n')
    }

    /**
     * Trims blank lines and the export's trailing separator.
     *
     * Only a horizontal rule standing on its own — surrounded by blank lines,
     * or flush with the start or end — divides messages. A `---` inside a
     * paragraph, such as the one a tool's output happens to contain, is part of
     * the text and must be kept.
     */
    private fun cleanBody(raw: String): String {
        val lines = raw.trim().lines()
        var end = lines.size
        while (end > 0 && (lines[end - 1].isBlank() || isStandaloneRule(lines, end - 1))) end--
        if (end == 0) return ""
        return lines.subList(0, end).joinToString("\n").trim()
    }

    private fun isStandaloneRule(lines: List<String>, index: Int): Boolean {
        if (hrRegex.matchEntire(lines[index].trim()) == null) return false
        val before = if (index == 0) "" else lines[index - 1]
        val after = if (index == lines.size - 1) "" else lines[index + 1]
        return before.isBlank() && after.isBlank()
    }
}
