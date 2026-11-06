package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.*
import org.junit.Test

class ExportCompatibilityTest {
    @Test
    fun chatboxSystemFenceCannotInjectMessages() {
        val md = listOf(
            "# Export", "", "**system**:", "", "````", "**user**:", "```", "example", "```", "````", "",
            "**user**:", "", "~~~", "Question", "~~~", "", "**assistant**:", "", "````markdown",
            "Example:", "```kotlin", "val value = 1", "```", "**user**:", "still an example", "````"
        ).joinToString("\n")
        val messages = ChatMarkdownParser.parse(md).messages
        assertEquals(2, messages.size)
        assertEquals("Question", messages[0].content)
        assertTrue(messages[1].content.contains("```kotlin"))
        assertTrue(messages[1].content.endsWith("still an example"))
    }

    @Test
    fun chatboxUnexpectedUnfencedTextIsNotDiscarded() {
        val md = "**assistant**:\n\n```\nFirst\n```\n\nUnexpected but meaningful\n\n```\nLast\n```"
        val message = ChatMarkdownParser.parse(md).messages.single()
        assertEquals("First\n\nUnexpected but meaningful\n\nLast", message.content)
    }

    @Test
    fun chatboxUnclosedFenceKeepsItsSource() {
        val md = "**assistant**:\n\n```\nAnswer never closed"
        assertEquals("```\nAnswer never closed", ChatMarkdownParser.parse(md).messages.single().content)
    }

    @Test
    fun kelivoCodeExamplesDoNotCreateTurnsToolsOrThoughts() {
        val md = listOf(
            "# Example", "", "> 2026年10月3日 16:02:10 · person", "", "Question", "", "---", "",
            "> 2026年10月3日 16:03:10 · model", "", "Example:", "", "````markdown",
            "> 2026年10月3日 16:04:10 · person", "[example_tool]", "**思考内容**", "```", "text", "```", "````", "", "Done"
        ).joinToString("\n")
        val messages = ChatMarkdownParser.parse(md).messages
        assertEquals(2, messages.size)
        assertEquals(listOf("text"), messages[1].blocks.map { it.kind })
        assertTrue(messages[1].content.contains("[example_tool]"))
        assertTrue(messages[1].content.endsWith("Done"))
    }

    @Test
    fun kelivoMalformedThinkingIsVisible() {
        val md = "# Example\n\n> 2026年10月3日 16:02:10 · person\n\nQuestion\n\n---\n\n> 2026年10月3日 16:03:10 · model\n\n**思考内容**\n\nNo code fence\n\nAnswer"
        val message = ChatMarkdownParser.parse(md).messages.last()
        assertTrue(message.content.contains("**思考内容**"))
        assertTrue(message.content.endsWith("Answer"))
    }

    @Test
    fun digitAndMiddleDotAloneAreNotKelivoEvidence() {
        val md = "# Notes\n\n> 123 examples · discussion\n\nMore notes"
        val message = ChatMarkdownParser.parse(md).messages.single()
        assertEquals(md, message.content)
        assertNull(message.speaker)
    }

    @Test
    fun ordinaryDocumentPreservesRoleTutorialAndQuotedFooter() {
        val md = "# Tutorial\n\nThis is how exports work:\n\n**Assistant**:\n\n> A quoted example\n\n**Tool**: `sample`"
        assertEquals(md, ChatMarkdownParser.parse(md).messages.single().content)
    }

    @Test
    fun genericUnclosedFencePreservesTrailingRuleAndRoleExample() {
        val body = "```markdown\n**User**:\n\n---"
        assertEquals(body, ChatMarkdownParser.parse("**Assistant**:\n\n$body").messages.single().content)
    }

    @Test
    fun imageInlineExamplesDoNotConsumeImageSlots() {
        val image = "![x](data:image/png;base64,AAAA)"
        val body = "`$image`\n\n\\$image\n\n$image"
        val message = ChatMarkdownParser.parse("**Assistant**:\n\n$body").messages.single()
        assertEquals(1, message.images.size)
        assertTrue(message.content.contains("`$image`"))
        assertTrue(message.content.contains("\\$image"))
        assertTrue(message.content.contains("[image:0]"))
    }

    @Test
    fun aHeadingInsideTheAnswerDoesNotBecomeTheConversationTitle() {
        val result = ChatMarkdownParser.parse("**Assistant**:\n\n# Answer heading\n\nText")
        assertNull(result.title)
        assertTrue(result.messages.single().content.startsWith("# Answer heading"))
    }

    @Test
    fun longToolOutputsStayBoundedAndKeepEveryAnswerInOrder() {
        val output = "x".repeat(12000) + " literal ``` and \\n"
        val body = (0 until 120).joinToString("\n\n") { index ->
            "**Tool**: `custom_$index`\nOutput:\n```text\n$output\n```\n\nAnswer $index"
        }
        val message = ChatMarkdownParser.parse("**Assistant**:\n\n$body").messages.single()
        assertEquals(240, message.blocks.size)
        for (index in 0 until 120) {
            assertEquals("tool", message.blocks[index * 2].kind)
            assertEquals("Answer $index", message.blocks[index * 2 + 1].text)
        }
        assertFalse(message.content.contains(output))
    }
}
