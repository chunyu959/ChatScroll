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

    // The time has to be there. A quoted sentence that merely happens to contain a
    // middle dot, such as a line of dialogue inside some other export, is not a turn.
    private val headerRegex = Regex(
        "^>\\s*((?:\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2})\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*[·•]\\s*(.+?)\\s*$"
    )
    private val thinkingLabel = "**思考内容**"
    private val toolHeaderRegex = Regex("^\\[([^\\[\\]]+)]$")

    private fun header(line: String): MatchResult? =
        if (line.startsWith("    ") || line.startsWith('\t')) null else headerRegex.matchEntire(line.trim())

    /** Recognition is confined to the start, never a quote inside another format. */
    fun isKelivo(content: String): Boolean {
        val lines = content.lines()
        val first = ChatMarkdownParser.firstContentLine(lines)
        return lines.getOrNull(first)?.let(::header) != null
    }

    fun parse(content: String): ChatMarkdownParser.Result {
        if (!isKelivo(content)) return ChatMarkdownParser.document(content)
        val lines = content.lines()
        val start = ChatMarkdownParser.firstContentLine(lines)
        val title = lines.take(start).firstOrNull { it.trim().startsWith("# ") }
            ?.trim()?.removePrefix("# ")?.trim()
        var fence: MarkdownFenceScanner.Fence? = null
        var role: ChatMarkdownParser.Role? = null
        var speaker: String? = null
        var time: String? = null
        var userName: String? = null
        val buffer = StringBuilder()
        val turns = ArrayList<Turn>()

        fun flush() {
            val currentRole = role ?: return
            // The --- between turns is a separator, not part of what was said.
            val body = ChatMarkdownParser.cleanBody(buffer.toString().lines())
            buffer.setLength(0)
            if (body.isEmpty()) return
            turns.add(Turn(currentRole, speaker, time, body))
        }

        for (raw in lines.drop(start)) {
            val active = fence
            if (active != null) {
                buffer.append(raw).append('\n')
                if (active.closes(raw)) fence = null
                continue
            }
            val opening = MarkdownFenceScanner.opening(raw)
            if (opening != null) {
                buffer.append(raw).append('\n')
                fence = opening
                continue
            }
            val header = header(raw)
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
            val blocks = if (turn.role == ChatMarkdownParser.Role.ASSISTANT) segment(turn.body)
                else listOf(ChatMarkdownParser.Block("text", turn.body))
            ChatMarkdownParser.message(turn.role, blocks, turn.speaker, turn.time)
        }

        return if (messages.isEmpty()) ChatMarkdownParser.document(content) else ChatMarkdownParser.Result(title, messages)
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
            val text = ChatMarkdownParser.trimBlankLines(prose)
            prose.clear()
            if (text.isNotEmpty()) blocks.add(ChatMarkdownParser.Block("text", text))
        }

        while (index < lines.size) {
            if (MarkdownFenceScanner.opening(lines[index]) != null) {
                val end = MarkdownFenceScanner.end(lines, index) ?: lines.size
                prose.addAll(lines.subList(index, end))
                index = end
                continue
            }
            if (!isIndented(lines[index]) && lines[index].trim() == thinkingLabel) {
                val thought = readThinking(lines, index + 1)
                if (thought != null) {
                    flushProse()
                    if (thought.second.isNotEmpty()) blocks.add(ChatMarkdownParser.Block("thinking", thought.second))
                    index = thought.first
                    continue
                }
            }

            val tool = if (isIndented(lines[index])) null else toolHeaderRegex.matchEntire(lines[index].trim())
            if (tool != null) {
                flushProse()
                val start = index + 1
                index = start
                while (index < lines.size && !isToolBoundary(lines, index)) {
                    if (MarkdownFenceScanner.opening(lines[index]) != null) {
                        index = MarkdownFenceScanner.end(lines, index) ?: lines.size
                    } else index++
                }
                val detail = ChatMarkdownParser.trimBlankLines(lines.subList(start, index))
                if (detail.isBlank()) prose.addAll(lines.subList(start - 1, index))
                else blocks.add(ChatMarkdownParser.Block("tool", detail, tool.groupValues[1].trim()))
                continue
            }

            prose.add(lines[index])
            index++
        }
        flushProse()

        return blocks
    }

    /** The fenced block after a thinking label, and where to resume after it. */
    private fun readThinking(lines: List<String>, from: Int): Pair<Int, String>? {
        var index = from
        while (index < lines.size && lines[index].isBlank()) index++
        val end = MarkdownFenceScanner.end(lines, index) ?: return null
        return end to ChatMarkdownParser.trimBlankLines(lines.subList(index + 1, end - 1))
    }

    /**
     * A tool call ends at the next tool, the next thought, or where the reply
     * begins. A fetched page is full of blank lines, links and headings, so a gap
     * alone is not the end: the line has to read as a sentence. Markup such as a
     * link, a heading or a bare date stays inside the tool.
     */
    private fun isIndented(line: String): Boolean = line.startsWith("    ") || line.startsWith('\t')

    private fun isToolBoundary(lines: List<String>, index: Int): Boolean {
        if (isIndented(lines[index])) return false
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

}
