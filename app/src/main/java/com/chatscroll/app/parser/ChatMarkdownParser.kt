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
 *     reply text
 *
 *     ---
 *
 * Tolerances:
 *  - role headers may be bold (`**User**:`) or plain (`User:`), and may carry
 *    inline content (`User: hello`);
 *  - trailing horizontal rules of each block (export separators) are removed,
 *    while thematic breaks inside a message body are preserved;
 *  - consecutive messages of the same role are merged;
 *  - if no role header is found at all, the whole file is kept as a single
 *    document message so that any Markdown file stays readable.
 */
object ChatMarkdownParser {

    enum class Role { USER, ASSISTANT }

    data class Message(val role: Role, val content: String)
    data class Result(val title: String?, val messages: List<Message>)

    private val titleRegex = Regex("^#\\s+(.+?)\\s*$")
    private val exportedRegex = Regex("^\\s*\\**\\s*Exported on .*$", RegexOption.IGNORE_CASE)
    private val roleRegex =
        Regex("^\\s*\\*{0,2}\\s*(User|Assistant)\\s*\\*{0,2}\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val hrRegex = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

    fun parse(content: String): Result {
        var title: String? = null
        var role: Role? = null
        val buffer = StringBuilder()
        val messages = ArrayList<Message>()

        fun flush() {
            val currentRole = role ?: return
            val body = cleanBody(buffer.toString())
            if (body.isNotEmpty()) {
                val last = messages.lastOrNull()
                if (last != null && last.role == currentRole) {
                    messages[messages.size - 1] = last.copy(content = last.content + "\n\n" + body)
                } else {
                    messages.add(Message(currentRole, body))
                }
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
        if (messages.isEmpty() && content.isNotBlank()) {
            messages.add(Message(Role.ASSISTANT, content.trim()))
        }

        return Result(title, messages)
    }

    private fun roleOf(match: MatchResult): Role =
        if (match.groupValues[1].equals("User", ignoreCase = true)) Role.USER else Role.ASSISTANT

    private fun appendInline(match: MatchResult, buffer: StringBuilder) {
        val rest = match.groupValues[2].trim()
        if (rest.isNotEmpty()) buffer.append(rest).append('\n')
    }

    /** Trims blank lines and trailing horizontal rules (export separators). */
    private fun cleanBody(raw: String): String {
        var text = raw.trim()
        while (text.isNotEmpty()) {
            val lastLine = text.substringAfterLast('\n', text).trim()
            if (hrRegex.matchEntire(lastLine) != null) {
                text = text.substringBeforeLast('\n', "").trim()
            } else {
                break
            }
        }
        return text.trim()
    }
}
