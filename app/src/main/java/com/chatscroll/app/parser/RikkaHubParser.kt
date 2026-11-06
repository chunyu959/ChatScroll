package com.chatscroll.app.parser

import com.chatscroll.app.parser.ChatMarkdownParser.Block

/** Ordered thinking, tool records, and answer text in role-header exports. */
internal object RikkaHubParser {
    private val toolHeader = Regex("\\*\\*Tool\\*\\*:\\s*`([^`]*)`")
    private val callId = Regex("-\\s+Call ID:\\s*`[^`]+`")

    fun segment(body: String): List<Block> {
        val lines = body.lines()
        val blocks = ArrayList<Block>()
        val prose = ArrayList<String>()
        var index = 0
        var mayThink = true

        fun flush() {
            val text = ChatMarkdownParser.trimBlankLines(prose)
            prose.clear()
            if (text.isNotEmpty()) blocks.add(Block("text", text))
        }

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                prose.add(line)
                index++
                continue
            }
            if (MarkdownFenceScanner.opening(line) != null) {
                val end = MarkdownFenceScanner.end(lines, index) ?: lines.size
                prose.addAll(lines.subList(index, end))
                index = end
                mayThink = false
                continue
            }
            val tool = readTool(lines, index)
            if (tool != null) {
                flush()
                blocks.add(tool.second)
                index = tool.first
                mayThink = true
                continue
            }
            if (mayThink && isQuote(line)) {
                flush()
                val thought = ArrayList<String>()
                while (index < lines.size && isQuote(lines[index])) {
                    thought.add(lines[index].trimStart().drop(1).removePrefix(" "))
                    index++
                }
                val text = ChatMarkdownParser.trimBlankLines(thought)
                if (text.isNotEmpty()) blocks.add(Block("thinking", text))
                continue
            }
            prose.add(line)
            mayThink = false
            index++
        }
        flush()
        return blocks
    }

    private fun isQuote(line: String): Boolean =
        !line.startsWith("    ") && !line.startsWith('\t') && line.trimStart().startsWith(">")

    /** Consumes only labelled, bounded sections, never the following answer. */
    private fun readTool(lines: List<String>, start: Int): Pair<Int, Block>? {
        if (lines[start].startsWith("    ") || lines[start].startsWith('\t')) return null
        val name = toolHeader.matchEntire(lines[start].trim())?.groupValues?.get(1) ?: return null
        var cursor = nextNonblank(lines, start + 1)
        if (cursor < lines.size && callId.matches(lines[cursor].trim())) {
            cursor = nextNonblank(lines, cursor + 1)
        }
        var end: Int? = null
        if (lines.getOrNull(cursor)?.trim() == "Input:") {
            val open = nextNonblank(lines, cursor + 1)
            end = MarkdownFenceScanner.end(lines, open) ?: return null
            cursor = nextNonblank(lines, end)
        }
        if (lines.getOrNull(cursor)?.trim() == "Output:") {
            cursor = nextNonblank(lines, cursor + 1)
            var imageEnd: Int? = null
            while (cursor < lines.size) {
                val next = MarkdownImageScanner.standaloneEnd(lines, cursor) ?: break
                imageEnd = next
                cursor = nextNonblank(lines, next)
            }
            end = if (lines.getOrNull(cursor)?.let(MarkdownFenceScanner::opening) != null) {
                MarkdownFenceScanner.end(lines, cursor) ?: return null
            } else {
                imageEnd ?: return null
            }
        }
        val next = end ?: return null
        val detail = ChatMarkdownParser.trimBlankLines(lines.subList(start + 1, next))
        return next to Block("tool", detail, name.ifBlank { null })
    }

    private fun nextNonblank(lines: List<String>, from: Int): Int {
        var index = from
        while (index < lines.size && lines[index].isBlank()) index++
        return index
    }
}
