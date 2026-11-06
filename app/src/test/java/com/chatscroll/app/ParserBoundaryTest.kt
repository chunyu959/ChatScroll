package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.*
import org.junit.Test

class ParserBoundaryTest {
    private fun tool(name: String = "example", output: String = "result", fence: String = "```") = listOf(
        "**Tool**: `$name`", "- Call ID: `opaque-id`", "Input:", "${fence}json",
        "{\"value\": 1}", fence, "Output:", "${fence}text", output, fence
    ).joinToString("\n")

    private fun reply(body: String) = ChatMarkdownParser.parse("**Assistant**:\n\n$body").messages.single()

    @Test
    fun closesToolAtOutputFenceForEveryKindOfAnswer() {
        for (answer in listOf("A normal answer.", "## Heading\n\nAnswer", "- first\n- second", "No punctuation", "**Bold opening**")) {
            for (gap in listOf("\n", "\n\n", "\n\n\n\n")) {
                val message = reply(tool() + gap + answer)
                assertEquals(listOf("tool", "text"), message.blocks.map { it.kind })
                assertEquals(answer, message.content)
                assertFalse(message.blocks.first().text.contains(answer))
            }
        }
    }

    @Test
    fun retainsProseBetweenToolsAndNormalQuotesAfterProse() {
        val message = reply("Before\n\n${tool()}\n\nMiddle\n\n> ordinary quotation\n\n${tool("other")}\n\nAfter\n\n> final quotation")
        assertEquals(listOf("text", "tool", "text", "tool", "text"), message.blocks.map { it.kind })
        assertTrue(message.content.contains("> ordinary quotation"))
        assertTrue(message.content.endsWith("> final quotation"))
    }

    @Test
    fun reopensThinkingWindowOnlyImmediatelyAfterAConfirmedTool() {
        val message = reply("> initial reasoning\n\n${tool()}\n\n> more reasoning\n\nAnswer\n\n> ordinary quotation")
        assertEquals(listOf("thinking", "tool", "thinking", "text"), message.blocks.map { it.kind })
        assertEquals("Answer\n\n> ordinary quotation", message.content)
    }

    @Test
    fun keepsTruncatedJsonAndInlineBackticksInsideTheirOutput() {
        val output = "[Tool output truncated: 10000 characters total]\nFull output saved to: /unused/example.txt\n\n{\"preview\":\"literal ``` and \\n **User**: unfinished"
        val message = reply(tool(output = output) + "\n\nAnswer")
        assertEquals("Answer", message.content)
        assertTrue(message.blocks.first().text.contains(output))
    }

    @Test
    fun matchesFenceCharacterLengthAndEntireClosingLine() {
        for (fence in listOf("````", "~~~~")) {
            val output = "```\n~~~\n${fence}not-a-close\n**User**:\n**Tool**: `fake`\n> not thinking"
            val message = reply(tool(output = output, fence = fence) + "\n\nAnswer")
            assertEquals(listOf("tool", "text"), message.blocks.map { it.kind })
            assertEquals("Answer", message.content)
            assertTrue(message.blocks.first().text.contains("**User**:"))
        }
    }

    @Test
    fun roleAndToolExamplesInsideCodeDoNotSplitMessages() {
        val body = "Example:\n\n````markdown\n**User**:\n**Tool**: `fake`\n> quoted example\n```\n````\n\nDone"
        val message = reply(body)
        assertEquals(body, message.content)
        assertEquals(listOf("text"), message.blocks.map { it.kind })
    }

    @Test
    fun ordinaryDocumentNeverInfersThinkingOrTools() {
        val md = "# Notes\n\n> opening quote\n\n${tool()}\n\n> closing quote"
        val message = ChatMarkdownParser.parse(md).messages.single()
        assertEquals(md, message.content)
        assertTrue(message.blocks.all { it.kind == "text" })
    }

    @Test
    fun formatExamplesCannotHijackAStartedTranscript() {
        val body = "Answer\n\n> 2026年10月3日 16:02:10 · example\n\n**user**:\n\n```\na code sample\n```"
        val md = "# Export\n\n**User**:\n\nQuestion\n\n---\n\n**Assistant**:\n\n$body"
        val result = ChatMarkdownParser.parse(md)
        assertEquals("Question", result.messages.first().content)
        assertNull(result.messages.first().speaker)
        assertTrue(result.messages.any { it.content.contains("Answer") })
    }

    @Test
    fun malformedToolStaysVisibleRatherThanSwallowingRemainder() {
        val body = "**Tool**: `broken`\nInput:\n```json\n{}\n```\nOutput:\n```text\nunfinished\n\nAnswer"
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.blocks.none { it.kind == "tool" })
    }

    @Test
    fun standaloneToolLabelWithoutSectionsIsJustText() {
        val body = "**Tool**: `documentation`\n\nThis explains a tool."
        assertEquals(body, reply(body).content)
    }

    @Test
    fun inputOnlyToolEndsAtItsOwnFence() {
        val message = reply("**Tool**: `pending`\nInput:\n```json\n{}\n```\n\nAnswer")
        assertEquals(listOf("tool", "text"), message.blocks.map { it.kind })
        assertEquals("Answer", message.content)
    }

    @Test
    fun finalizesImagesOnceAcrossTextAndMultimodalTools() {
        val first = "data:image/png;base64," + "A".repeat(120)
        val second = "data:image/png;base64," + "B".repeat(120)
        val third = "data:image/png;base64," + "C".repeat(120)
        val call = tool().replace("Output:\n", "Output:\n![output]($second)\n")
        val message = reply("![before]($first)\n\n$call\n\n![after]($third)\n\nAnswer")
        assertEquals(listOf(first, second, third), message.images)
        assertTrue(message.blocks[0].text.contains("[image:0]"))
        assertTrue(message.blocks[1].text.contains("[image:1]"))
        assertTrue(message.blocks[2].text.contains("[image:2]"))
        assertFalse(message.content.contains("[image:1]"))
        assertTrue(message.blocks.none { it.text.contains("data:image/") })
        assertEquals(message.blocks.filter { it.kind == "text" }.joinToString("\n\n") { it.text }, message.content)
    }

    @Test
    fun imageExamplesInsideCodeRemainLiteral() {
        val body = "```markdown\n![example](data:image/png;base64,AAAA)\n```"
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.images.isEmpty())
    }

    @Test
    fun handlesCrLfAndNonstandardOpaqueToolNames() {
        val body = (tool("custom-plugin/do_work.2") + "\n\nAnswer").replace("\n", "\r\n")
        val message = reply(body)
        assertEquals("Answer", message.content)
        assertEquals("custom-plugin/do_work.2", message.blocks.first().name)
    }
}
