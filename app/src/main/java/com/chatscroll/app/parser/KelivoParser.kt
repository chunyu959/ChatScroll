package com.chatscroll.app.parser

/**
 * Parser for chat transcripts exported by Kelivo.
 *
 * The layout is nothing like a role-header export. Every turn opens with a
 * blockquote carrying the time and the speaker's name:
 *
 *     > 2026年10月3日 16:02:10 · 醒栖
 *
 *     the message
 *
 *     ---
 *
 *     > 2026年10月3日 16:02:10 · gemini
 *
 *     **思考内容**
 *
 *     ```text
 *     the model's reasoning
 *     ```
 *
 *     [search_web]
 *     the tool's output
 *
 *     the reply
 *
 * Who is the user is not fixed by name. The first speaker is taken to be the
 * user and every later speaker the assistant, so the same file reads correctly
 * whatever the account or the model is called.
 *
 * Reasoning and tool calls are returned as collapsible blocks, in the order they
 * happened, exactly as the role-header parser does for its own format.
 */
object KelivoParser {

    private val headerRegex = Regex("^>\\s*(.+?)\\s*[·•]\\s*(.+?)\\s*$")
    private val thinkingLabel = "**思考内容**"
    private val fenceRegex = Regex("^\\s*(```+|~~~+).*")
    private val toolHeaderRegex = Regex("^\\[([^\\[\\]]+)]$")

    /** True when the file opens its turns the way a Kelivo export does. */
    fun isKelivo(content: String): Boolean =
        content.lineSequence().any { headerRegex.matches(it.trim()) }

    fun parse(content: String): ChatMarkdownParser.Result {
        var title: String? = null
        var titleSettled = false
        var role: ChatMarkdownParser.Role? = null
        var speaker: String? = null
        var time: String? = null
        var userName: String? = null
        val buffer = StringBuilder()
        val turns = ArrayList<Turn>()

        fun flush() {
            val currentRole = role ?: return
            // The --- between turns is a separator, not part of what was said.
            val body = buffer.toString().trim().replace(Regex("(?m)\\n+---\\s*$"), "").trim()
            buffer.setLength(0)
            if (body.isEmpty()) return
            turns.add(Turn(currentRole, speaker, time, body))
        }

        for (raw in content.lines()) {
            if (!titleSettled) {
                title = raw.removePrefix("#").trim().ifBlank { null }
                titleSettled = true
                continue
            }

            val header = headerRegex.matchEntire(raw.trim())
            if (header != null) {
                flush()
                val name = header.groupValues[2].trim()
                if (userName == null) userName = name
                role = if (name == userName) {
                    ChatMarkdownParser.Role.USER
                } else {
                    ChatMarkdownParser.Role.ASSISTANT
                }
                speaker = name
                time = shortenTime(header.groupValues[1].trim())
            } else if (role != null) {
                buffer.append(raw).append('\n')
            }
        }
        flush()

        val messages = turns.map { turn ->
            if (turn.role == ChatMarkdownParser.Role.ASSISTANT) {
                val blocks = segment(turn.body)
                val text = blocks.filter { it.kind == "text" }.joinToString("\n\n") { it.text }
                val (body, images) = splitImages(text)
                ChatMarkdownParser.Message(
                    turn.role, body, null, images, blocks, turn.speaker, turn.time
                )
            } else {
                val (body, images) = splitImages(turn.body)
                ChatMarkdownParser.Message(
                    turn.role, body, null, images, emptyList(), turn.speaker, turn.time
                )
            }
        }

        return ChatMarkdownParser.Result(title, messages)
    }

    private data class Turn(
        val role: ChatMarkdownParser.Role,
        val speaker: String?,
        val time: String?,
        val body: String
    )

    /**
     * Pulls reasoning and tool calls out of an assistant turn, keeping the reply's
     * own text where it was written.
     *
     * Reasoning is the fenced block that follows a `**思考内容**` label. A tool call
     * is a line holding nothing but `[name]`; everything up to the next tool, the
     * next thought, or the reply belongs to it. The reply's text stays between
     * them rather than being gathered at the end.
     */
    private fun segment(body: String): List<ChatMarkdownParser.Block> {
        val lines = body.lines()
        val blocks = ArrayList<ChatMarkdownParser.Block>()
        val prose = ArrayList<String>()
        var index = 0

        fun flushProse() {
            val text = prose.joinToString("\n").trim()
            prose.clear()
            if (text.isNotEmpty()) blocks.add(ChatMarkdownParser.Block("text", text))
        }

        while (index < lines.size) {
            if (lines[index].trim() == thinkingLabel) {
                flushProse()
                val (next, text) = readThinking(lines, index + 1)
                if (text.isNotEmpty()) blocks.add(ChatMarkdownParser.Block("thinking", text))
                index = next
                continue
            }

            val tool = toolHeaderRegex.matchEntire(lines[index].trim())
            if (tool != null) {
                flushProse()
                val start = index + 1
                index = start
                while (index < lines.size && !isToolBoundary(lines, index)) index++
                val detail = lines.subList(start, index).joinToString("\n").trim()
                blocks.add(ChatMarkdownParser.Block("tool", detail, tool.groupValues[1].trim()))
                continue
            }

            prose.add(lines[index])
            index++
        }
        flushProse()

        return blocks
    }

    /** The fenced block after a thinking label, and where to resume after it. */
    private fun readThinking(lines: List<String>, from: Int): Pair<Int, String> {
        var index = from
        while (index < lines.size && lines[index].isBlank()) index++
        if (index >= lines.size) return index to ""

        val fence = fenceRegex.matchEntire(lines[index].trim()) ?: return index to ""
        val marker = fence.groupValues[1].take(3)
        index++
        val collected = ArrayList<String>()
        while (index < lines.size && !lines[index].trim().startsWith(marker)) {
            collected.add(lines[index])
            index++
        }
        if (index < lines.size) index++
        return index to collected.joinToString("\n").trim()
    }

    /**
     * A tool call ends at the next tool, the next thought, or where the reply
     * begins. A fetched page is full of blank lines, links and headings, so a gap
     * alone is not the end: the line has to read as a sentence. Markup such as a
     * link, a heading or a bare date stays inside the tool.
     */
    private fun isToolBoundary(lines: List<String>, index: Int): Boolean {
        val trimmed = lines[index].trim()
        if (trimmed == thinkingLabel) return true
        if (toolHeaderRegex.matches(trimmed)) return true
        if (trimmed.isEmpty()) return false
        val previousBlank = index > 0 && lines[index - 1].isBlank()
        if (!previousBlank) return false
        if (trimmed.first() in "#>*-|[]!`") return false
        return trimmed.contains(Regex("[.?!。？！]"))
    }

    /** "2026年10月3日 16:02:10" becomes "10-03 16:02". Anything else is kept as it is. */
    private fun shortenTime(raw: String): String {
        val match = Regex("(\\d{1,2})月(\\d{1,2})日\\s+(\\d{2}:\\d{2})").find(raw) ?: return raw
        val month = match.groupValues[1].padStart(2, '0')
        val day = match.groupValues[2].padStart(2, '0')
        return "$month-$day ${match.groupValues[3]}"
    }

    /** Lifts embedded images out of the text, leaving a short marker behind. */
    private fun splitImages(content: String): Pair<String, List<String>> {
        if (!content.contains("![")) return content to emptyList()
        val images = ArrayList<String>()
        val text = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .replace(content) { match ->
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
}
