package com.chatscroll.app.parser

/**
 * Parser for chat transcripts exported by Chatbox.
 *
 * Each turn is a bold, lower-case role label whose body sits inside a fence:
 *
 *     **system**:
 *
 *     ```
 *     You are a helpful assistant.
 *     ```
 *
 *     **user**:
 *
 *     ```
 *     the question
 *     ```
 *
 *     **assistant**:
 *
 *     ```
 *     the start of the reply
 *
 *     ```
 *
 *     Tool Call: web_search (state: result)
 *     Args:
 *       { ... }
 *     Result:
 *       { ... }
 *
 *     ```
 *     the rest of the reply
 *     ```
 *
 * The role labels are what sets this apart from a RikkaHub export, which writes
 * `**User**:` with a capital letter and puts the text straight under it. A file is
 * only treated as Chatbox when such a label is followed, after blank lines, by a
 * fence, so a stray `**user**:` inside some other document is left alone.
 *
 * A tool call is a `Tool Call: name` line standing between the fences. It is
 * returned as a collapsible block, and each fenced stretch of the reply stays
 * where it was, so the text reads between the tools instead of after all of them.
 * Chatbox exports carry neither images nor the model's reasoning, only an
 * `[image]` placeholder, which is kept as ordinary text.
 */
object ChatboxParser {

    private val roleRegex = Regex("^\\*\\*(system|user|assistant)\\*\\*:\\s*$")
    private val fenceRegex = Regex("^\\s*(```+|~~~+).*")
    private val toolCallRegex = Regex("^Tool Call:\\s*(\\S+).*")

    /** True when the file opens its turns the way a Chatbox export does. */
    fun isChatbox(content: String): Boolean {
        val lines = content.lines()
        for (index in lines.indices) {
            if (roleRegex.matches(lines[index].trim()) && fenceAfter(lines, index) != null) {
                return true
            }
        }
        return false
    }

    fun parse(content: String): ChatMarkdownParser.Result {
        var title: String? = null
        val messages = ArrayList<ChatMarkdownParser.Message>()

        var role: ChatMarkdownParser.Role? = null
        var inFence = false
        var fenceMarker = ""
        val fence = StringBuilder()
        val parts = ArrayList<ChatMarkdownParser.Block>()
        var toolName: String? = null
        val tool = StringBuilder()

        fun endTool() {
            val name = toolName ?: return
            val detail = tool.toString().trim()
            if (detail.isNotEmpty()) parts.add(ChatMarkdownParser.Block("tool", detail, name))
            toolName = null
            tool.setLength(0)
        }

        fun endTurn() {
            if (role == null) return
            endTool()
            val body = parts.filter { it.kind == "text" }.joinToString("\n\n") { it.text }
            if (body.isNotEmpty() || parts.isNotEmpty()) {
                messages.add(ChatMarkdownParser.Message(role!!, body, null, emptyList(), parts.toList()))
            }
            role = null
            parts.clear()
        }

        for (raw in content.lines()) {
            val trimmed = raw.trim()

            if (title == null && trimmed.startsWith("# ")) {
                title = trimmed.removePrefix("#").trim().ifBlank { null }
            }

            if (!inFence && roleRegex.matches(trimmed)) {
                endTurn()
                role = when (roleRegex.matchEntire(trimmed)!!.groupValues[1]) {
                    "user" -> ChatMarkdownParser.Role.USER
                    "assistant" -> ChatMarkdownParser.Role.ASSISTANT
                    else -> null
                }
                continue
            }

            if (role == null) continue

            if (inFence) {
                if (trimmed.startsWith(fenceMarker)) {
                    inFence = false
                    val text = fence.toString().trim()
                    if (text.isNotEmpty()) parts.add(ChatMarkdownParser.Block("text", text))
                    fence.setLength(0)
                } else {
                    fence.append(raw).append('\n')
                }
                continue
            }

            val fenceMatch = fenceRegex.matchEntire(trimmed)
            if (fenceMatch != null) {
                endTool()
                inFence = true
                fenceMarker = fenceMatch.groupValues[1].take(3)
                continue
            }

            val toolMatch = toolCallRegex.matchEntire(trimmed)
            if (toolMatch != null && role == ChatMarkdownParser.Role.ASSISTANT) {
                endTool()
                toolName = toolMatch.groupValues[1]
                continue
            }

            if (toolName != null) tool.append(raw).append('\n')
        }
        if (inFence) {
            val text = fence.toString().trim()
            if (text.isNotEmpty()) parts.add(ChatMarkdownParser.Block("text", text))
        }
        endTurn()

        return ChatMarkdownParser.Result(title, messages)
    }

    /** The index of the fence that opens the body after a role label, if there is one. */
    private fun fenceAfter(lines: List<String>, roleIndex: Int): Int? {
        var index = roleIndex + 1
        while (index < lines.size && lines[index].isBlank()) index++
        if (index >= lines.size || !fenceRegex.matches(lines[index].trim())) return null
        return index
    }
}
