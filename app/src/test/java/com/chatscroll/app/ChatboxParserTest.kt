package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chatbox exports wrap every turn in a fence under a lower-case role label and
 * write tool calls as `Tool Call: name` lines between those fences. These check
 * that such a file is told apart from the other two formats, that the system
 * prompt and the footer are dropped, and that tool calls fold out of the reply.
 */
class ChatboxParserTest {

    private val sample = """
        # 测试工具与数学公式

        ## 1. 测试工具与数学公式

        **system**:

        ```
        You are a helpful assistant.
        ```

        **user**:

        ```
        这是一个测试对话，请你使用所有工具
        ```

        **assistant**:

        ```
        好的，我来测试所有工具。

        ```

        Tool Call: web_search (state: result)
        Args:
          {
            "query": "Euler identity"
          }
        Result:
          {
            "title": "Euler's identity"
          }

        ```
        两个工具已测试完毕。

        ${'$'}${'$'}e^{i\pi} + 1 = 0${'$'}${'$'}
        ```

        **user**:

        ```
        图片测试
        [image]
        ```

        --------------------

        <a href="https://chatboxai.app"><b>Chatbox AI</b></a>
    """.trimIndent()

    @Test
    fun readsTurnsAndDropsTheSystemPromptAndFooter() {
        val result = ChatMarkdownParser.parse(sample)

        assertEquals("测试工具与数学公式", result.title)
        assertEquals(3, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("这是一个测试对话，请你使用所有工具", result.messages[0].content)
        assertFalse(result.messages.any { it.content.contains("helpful assistant") })
        assertFalse(result.messages.any { it.content.contains("chatboxai.app") })
    }

    @Test
    fun foldsToolCallsOutOfTheReply() {
        val reply = ChatMarkdownParser.parse(sample).messages[1]

        assertEquals(ChatMarkdownParser.Role.ASSISTANT, reply.role)
        val tools = reply.blocks.filter { it.kind == "tool" }
        assertEquals(listOf("web_search"), tools.map { it.name })
        assertTrue(tools[0].text.contains("Euler identity"))
        assertFalse(reply.content.contains("Tool Call"))
        assertTrue(reply.content.contains("两个工具已测试完毕"))
        assertTrue(reply.content.contains("${'$'}${'$'}e^{i\\pi} + 1 = 0${'$'}${'$'}"))

        // The reply is written around the tool call, so its text stays on either side.
        val order = reply.blocks.map { it.kind }
        assertEquals(listOf("text", "tool", "text"), order)
        assertTrue(reply.blocks.first().text.startsWith("好的，我来测试所有工具"))
    }

    @Test
    fun keepsTheImagePlaceholderAsPlainText() {
        val image = ChatMarkdownParser.parse(sample).messages[2]
        assertEquals("图片测试\n[image]", image.content)
        assertTrue(image.images.isEmpty())
    }

    @Test
    fun parsesASelfContainedExportWithInterleavedTools() {
        val md = """
            # Synthetic tool exercise

            **system**:

            ```
            This synthetic system prompt must be omitted.
            ```

            **user**:

            ```
            Exercise the available tools with synthetic data.
            ```

            **assistant**:

            ```
            Starting the tool exercise.
            ```

            Tool Call: web_search (state: result)
            Args:
              {"query":"polygon reference"}
            Result:
              {"searchResults":[{"title":"Polygon reference"}]}

            Tool Call: parse_link (state: result)
            Args:
              {"url":"https://example.invalid/polygons"}
            Result:
              {"title":"Polygon reference","sides":6}

            ```
            The two reference tools have finished.
            ```

            Tool Call: save_memory (state: result)
            Args:
              {"content":"Synthetic note"}
            Result:
              {"id":"fixture-note"}

            ```
            Next I will call delete_memory for the synthetic note.
            ```

            Tool Call: delete_memory (state: result)
            Args:
              {"id":"fixture-note"}
            Result:
              {"deleted":true}

            ```
            All synthetic tools have finished.
            ```

            **user**:

            ```
            An image placeholder follows.
            [image]
            ```

            **assistant**:

            ```
            The image is represented by a placeholder.
            ```

            **user**:

            ```
            Describe the supported formatting.
            ```

            **assistant**:

            ```
            Tables and code blocks are supported.
            ```

            --------------------

            <a href="https://chatboxai.app"><b>Chatbox AI</b></a>
        """.trimIndent()
        val result = ChatMarkdownParser.parse(md)

        assertEquals("Synthetic tool exercise", result.title)
        assertEquals(6, result.messages.size)
        assertEquals(ChatMarkdownParser.Role.USER, result.messages[0].role)
        assertEquals("Exercise the available tools with synthetic data.", result.messages[0].content)

        val reply = result.messages[1]
        assertEquals(
            listOf("web_search", "parse_link", "save_memory", "delete_memory"),
            reply.blocks.filter { it.kind == "tool" }.map { it.name }
        )
        assertTrue(reply.blocks.first { it.name == "parse_link" }.text.contains("Polygon reference"))
        assertTrue(reply.content.contains("All synthetic tools have finished."))
        assertFalse(reply.content.contains("Tool Call"))
        assertFalse(reply.content.contains("searchResults"))

        // Each remark stays between the tool calls it describes.
        assertEquals(
            listOf("text", "tool", "tool", "text", "tool", "text", "tool", "text"),
            reply.blocks.map { it.kind }
        )
        assertEquals("The two reference tools have finished.", reply.blocks[3].text)
        assertTrue(reply.blocks[5].text.contains("delete_memory"))
        assertTrue(result.messages[2].content.contains("[image]"))
        assertTrue(result.messages[2].images.isEmpty())
        assertEquals("Tables and code blocks are supported.", result.messages[5].content)
        assertFalse(result.messages.any { it.content.contains("system prompt") })
        assertFalse(result.messages.any { it.content.contains("chatboxai.app") })
    }

    @Test
    fun keepsQuotedSentencesFromBeingReadAsAnotherFormat() {
        // A middle dot in quoted dialogue must not become a Kelivo turn header.
        val quotedSentence = "> A fictional guide · a sentence without a timestamp."
        val expected = (0 until 45).flatMap { turn ->
            listOf(
                "Synthetic user turn $turn.",
                "Synthetic assistant turn $turn." +
                    if (turn == 20) "\n\n$quotedSentence\n\nThe same reply continues." else ""
            )
        }
        val md = buildString {
            append("# Synthetic quoted conversation\n\n")
            expected.forEachIndexed { index, body ->
                val role = if (index % 2 == 0) "user" else "assistant"
                append("**$role**:\n\n```\n$body\n```\n\n")
            }
        }
        val result = ChatMarkdownParser.parse(md)

        assertEquals("Synthetic quoted conversation", result.title)
        assertEquals(90, result.messages.size)
        expected.forEachIndexed { index, body ->
            val role = if (index % 2 == 0) ChatMarkdownParser.Role.USER else ChatMarkdownParser.Role.ASSISTANT
            assertEquals("turn $index", role, result.messages[index].role)
            assertEquals("turn $index", body, result.messages[index].content)
        }
        assertTrue(result.messages[41].content.contains(quotedSentence))
    }

    @Test
    fun leavesARoleHeaderExportUntouched() {
        val md = """
            # 测试

            **User**:

            你好

            ---

            **Assistant**:

            好的
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)
        assertEquals(2, result.messages.size)
        assertEquals("你好", result.messages[0].content)
        assertEquals("好的", result.messages[1].content)
    }

    @Test
    fun leavesAKelivoExportUntouched() {
        val md = """
            # 新对话

            > 2026年10月3日 16:02:10 · 醒栖

            你好

            ---

            > 2026年10月3日 16:02:10 · gemini

            好的
        """.trimIndent()

        val result = ChatMarkdownParser.parse(md)
        assertEquals(2, result.messages.size)
        assertEquals("醒栖", result.messages[0].speaker)
        assertEquals("你好", result.messages[0].content)
    }
}
