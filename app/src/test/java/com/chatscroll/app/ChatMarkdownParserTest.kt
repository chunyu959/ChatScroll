package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownParserTest {

    @Test
    fun parsesStandardExport() {
        val md = """
            # 测试对话

            *Exported on 2026/10/01 21:00:00*

            **User**:

            你好，帮我写个 Hello World

            ---

            **Assistant**:

            当然：

            ```kotlin
            fun main() {
                println("Hello")
            }
            ```

            ---

            **User**:

            谢谢！

            ---
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals("测试对话", result.title)
        assertEquals(3, result.messages.size)

        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("你好，帮我写个 Hello World", result.messages[0].content)

        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[1].role)
        assertNull(result.messages[1].thinking)
        assertTrue(result.messages[1].content.contains("```kotlin"))
        assertTrue(result.messages[1].content.contains("println"))
        assertTrue(!result.messages[1].content.trimEnd().endsWith("---"))

        assertEquals(ChatMarkdownParser.Role.USER, result.messages[2].role)
        assertEquals("谢谢！", result.messages[2].content)
    }

    @Test
    fun parsesPlainHeadersAndInlineContent() {
        val md = """
            User:
            hi there

            ---

            Assistant: hello, how can I help?
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertNull(result.title)
        assertEquals(2, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("hi there", result.messages[0].content)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[1].role)
        assertEquals("hello, how can I help?", result.messages[1].content)
    }

    @Test
    fun extractsLeadingThinkingFromAssistantMessage() {
        val md = """
            **Assistant**:

            > Let me analyze this step by step.
            > First, we consider the options.

            Here is my final answer.
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(1, result.messages.size)
        val message = result.messages[0]
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, message.role)
        assertEquals(1, message.blocks.size)
        assertEquals("thinking", message.blocks[0].kind)
        assertEquals(
            "Let me analyze this step by step.\nFirst, we consider the options.",
            message.blocks[0].text
        )
        assertEquals("Here is my final answer.", message.content)
    }

    @Test
    fun capturesThinkingBeforeAndAfterToolCalls() {
        val md = """
            **Assistant**:

            > First thought.

            **Tool**: `memory_tool`

            - Call ID: `memory_tool_0`
            Input:
            ```json
            {"action": "create"}
            ```

            > Second thought, after the tool.

            The actual reply.
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        val message = result.messages[0]
        assertEquals(3, message.blocks.size)
        assertEquals("thinking", message.blocks[0].kind)
        assertEquals("First thought.", message.blocks[0].text)
        assertEquals("tool", message.blocks[1].kind)
        assertEquals("memory_tool", message.blocks[1].name)
        assertTrue(message.blocks[1].text.contains("memory_tool_0"))
        assertEquals("thinking", message.blocks[2].kind)
        assertEquals("Second thought, after the tool.", message.blocks[2].text)
        assertEquals("The actual reply.", message.content.trim())
        assertTrue(!message.content.contains("**Tool**"))
    }

    @Test
    fun doesNotTouchQuotesInsideBody() {
        val md = """
            **Assistant**:

            Here is a quote:

            > some famous saying

            That's it.
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(1, result.messages.size)
        assertNull(result.messages[0].thinking)
        assertTrue(result.messages[0].content.contains("> some famous saying"))
    }

    @Test
    fun keepsConsecutiveSameRoleMessagesSeparate() {
        // A compressed export puts a recap and the first real message one after
        // the other, both marked User. They must stay two bubbles.
        val md = """
            **User**:

            [recap of the earlier conversation]

            ---

            **User**:

            the actual question

            ---

            **Assistant**:

            answer
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(3, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("[recap of the earlier conversation]", result.messages[0].content)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[1].role)
        assertEquals("the actual question", result.messages[1].content)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[2].role)
    }

    @Test
    fun keepsInteriorHorizontalRulesButStripsTrailingOnes() {
        val md = """
            **Assistant**:

            before

            ---

            after

            ---
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(1, result.messages.size)
        assertEquals("before\n\n---\n\nafter", result.messages[0].content)
    }

    @Test
    fun fallsBackToSingleDocumentWhenNoHeaders() {
        val md = """
            # Just a document

            Some plain markdown content.
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        // title is recognized from the heading
        assertEquals("Just a document", result.title)
        assertEquals(1, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.ASSISTANT, result.messages[0].role)
        assertTrue(result.messages[0].content.contains("Some plain markdown content."))
    }

    @Test
    fun unwrapsLineWrappedBase64Images() {
        val chunk = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ"
        val wrapped = "![Image](data:image/png;base64,$chunk\n$chunk\n$chunk)"

        val md = """
            **Assistant**:

            Here is your image:

            $wrapped
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        assertEquals(1, result.messages.size)
        val message = result.messages[0]
        // the wrapped payload is pulled out into one clean image, not left in the text
        assertEquals(1, message.images.size)
        val url = message.images[0]
        assertTrue(!url.any { it.isWhitespace() })
        assertTrue(url.startsWith("data:image/png;base64,"))
        assertTrue(message.content.contains("Here is your image:"))
        assertTrue(!message.content.contains(chunk))
    }

    @Test
    fun wrapsRawBase64WithDetectedMime() {
        // Built without trimIndent(): that call strips leading whitespace from
        // every line and would inject spaces into the base64 payload itself.
        val raw = "iVBORw0KGgoAAAANSUhEUgAA" + "A".repeat(150) + "=="
        val md = "**Assistant**:\n\n![Image]($raw)"

        val result = ChatMarkdownParser.parse(md)

        val message = result.messages[0]
        assertEquals(1, message.images.size)
        assertTrue(message.images[0].startsWith("data:image/png;base64,"))
        assertTrue(!message.content.contains(raw))
    }

    @Test
    fun splitsEmbeddedImagesOutOfTheText() {
        val payload = "/9j/" + "A".repeat(200)
        val md = """
            **User**:

            look at this

            ![Image](data:image/jpeg;base64,$payload)

            what do you think
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        val message = result.messages[0]
        assertEquals(1, message.images.size)
        assertTrue(message.images[0].startsWith("data:image/jpeg;base64,"))
        // the payload must not remain in the text that gets laid out
        assertTrue(!message.content.contains(payload))
        assertTrue(message.content.contains("[image:0]"))
        assertTrue(message.content.contains("look at this"))
        assertTrue(message.content.contains("what do you think"))
    }

    @Test
    fun leavesRegularLinksAndImagesUntouched() {
        val md = """
            **Assistant**:

            ![logo](https://example.com/logo.png) and [a link](https://example.com)
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)

        val content = result.messages[0].content
        assertTrue(content.contains("![logo](https://example.com/logo.png)"))
        assertTrue(content.contains("[a link](https://example.com)"))
    }
}
