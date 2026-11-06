package com.chatscroll.app.parser

/** Chatbox wraps body stretches in fences and writes tool calls between them. */
object ChatboxParser {
    private val roleRegex = Regex("^\\*\\*(system|user|assistant)\\*\\*:\\s*$")
    private val toolCallRegex = Regex("^Tool Call:\\s*(\\S+).*")

    private fun header(line: String): MatchResult? =
        if (line.startsWith("    ") || line.startsWith('\t')) null else roleRegex.matchEntire(line.trim())

    internal fun hasOpeningHeader(content: String): Boolean {
        val lines = content.lines()
        return lines.getOrNull(ChatMarkdownParser.firstContentLine(lines))?.let(::header) != null
    }

    fun isChatbox(content: String): Boolean {
        val lines = content.lines()
        val first = ChatMarkdownParser.firstContentLine(lines)
        if (lines.getOrNull(first)?.let(::header) == null) return false
        val next = nextNonblank(lines, first + 1)
        return next < lines.size && MarkdownFenceScanner.opening(lines[next]) != null
    }

    fun parse(content: String): ChatMarkdownParser.Result {
        if (!hasOpeningHeader(content)) return ChatMarkdownParser.document(content)
        val lines = content.lines()
        val start = ChatMarkdownParser.firstContentLine(lines)
        val title = lines.take(start).firstOrNull { it.trim().startsWith("# ") }
            ?.trim()?.removePrefix("# ")?.trim()
        val messages = ArrayList<ChatMarkdownParser.Message>()
        val parts = ArrayList<ChatMarkdownParser.Block>()
        val prose = ArrayList<String>()
        val tool = ArrayList<String>()
        var toolName: String? = null
        var role: String? = null
        var index = start

        fun flushProse() {
            val text = ChatMarkdownParser.trimBlankLines(prose)
            if (text.isNotEmpty()) parts.add(ChatMarkdownParser.Block("text", text))
            prose.clear()
        }
        fun endTool() {
            toolName?.let { parts.add(ChatMarkdownParser.Block("tool", ChatMarkdownParser.trimBlankLines(tool), it)) }
            tool.clear()
            toolName = null
        }
        fun endTurn() {
            flushProse()
            endTool()
            if (role != null && role != "system" && parts.isNotEmpty()) {
                messages.add(ChatMarkdownParser.message(
                    if (role == "user") ChatMarkdownParser.Role.USER else ChatMarkdownParser.Role.ASSISTANT,
                    parts.toList()
                ))
            }
            parts.clear()
        }

        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            val roleHeader = header(raw)
            if (roleHeader != null) {
                endTurn()
                role = roleHeader.groupValues[1]
                index++
                continue
            }
            if (MarkdownFenceScanner.opening(raw) != null) {
                flushProse()
                endTool()
                val end = MarkdownFenceScanner.end(lines, index)
                // An incomplete body stays readable with its original delimiter.
                val body = if (end == null) lines.subList(index, lines.size) else lines.subList(index + 1, end - 1)
                val text = ChatMarkdownParser.trimBlankLines(body)
                if (text.isNotEmpty()) parts.add(ChatMarkdownParser.Block("text", text))
                index = end ?: lines.size
                continue
            }
            // The known footer is outside all body fences and follows a long rule.
            if (trimmed.matches(Regex("-{10,}"))) {
                val next = nextNonblank(lines, index + 1)
                if (lines.getOrNull(next)?.contains("<a href=\"https://chatboxai.app") == true) break
            }
            val call = if (raw.startsWith("    ") || raw.startsWith('\t')) null else toolCallRegex.matchEntire(trimmed)
            if (call != null && role == "assistant") {
                flushProse()
                endTool()
                toolName = call.groupValues[1]
            } else if (toolName != null) {
                tool.add(raw)
            } else if (role != null) {
                prose.add(raw)
            }
            index++
        }
        endTurn()
        return if (messages.isEmpty()) ChatMarkdownParser.document(content) else ChatMarkdownParser.Result(title, messages)
    }

    private fun nextNonblank(lines: List<String>, from: Int): Int {
        var index = from
        while (index < lines.size && lines[index].isBlank()) index++
        return index
    }
}
