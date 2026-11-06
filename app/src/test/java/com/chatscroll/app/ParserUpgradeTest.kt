package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import com.chatscroll.app.parser.ChatboxParser
import com.chatscroll.app.parser.KelivoParser
import org.junit.Assert.*
import org.junit.Test

class ParserUpgradeTest {
    private val data = "data:image/png;base64,AAAA"
    private fun reply(body: String) = ChatMarkdownParser.parse("**Assistant**:\n\n$body").messages.single()

    @Test
    fun exposesVersionAndStableFormatNames() {
        assertEquals(2, ChatMarkdownParser.VERSION)
        val examples = mapOf(
            "markdown" to "# Notes\n\nOrdinary document",
            "rikkahub" to "# Conversation\n\nUser: question\n\nAssistant: answer",
            "chatbox" to "**user**:\n\n```\nquestion\n```",
            "kelivo" to "> 2030年6月12日 09:30:00 · reader\n\nquestion"
        )
        examples.forEach { (format, source) ->
            assertEquals(format, ChatMarkdownParser.detectFormat(source))
            assertEquals(format, ChatMarkdownParser.detectFormat("﻿$source"))
            assertFalse(ChatMarkdownParser.parse(source, format).messages.isEmpty())
        }
        assertEquals("markdown", ChatMarkdownParser.detectFormat("\n \n"))
    }

    @Test
    fun ambiguousLowercaseLabelsNeedAnExplicitFormat() {
        val source = "user: question\n\nassistant: answer"
        assertEquals("markdown", ChatMarkdownParser.detectFormat(source))
        assertEquals(source, ChatMarkdownParser.parse(source).messages.single().content)
        assertEquals(listOf("question", "answer"), ChatMarkdownParser.parse(source, "rikkahub").messages.map { it.content })
        val bold = "**user**:\n\nquestion\n\n**assistant**:\n\nanswer"
        assertEquals("markdown", ChatMarkdownParser.detectFormat(bold))
        assertEquals(listOf("question", "answer"), ChatMarkdownParser.parse(bold, "chatbox").messages.map { it.content })
    }

    @Test
    fun markdownOverrideAndLeadingTutorialProseRemainLiteral() {
        val roles = "**Assistant**:\n\n> reasoning example\n\nUser: example"
        assertEquals(roles, ChatMarkdownParser.parse(roles, "markdown").messages.single().content)
        val tutorial = "# Export formats\n\nA tutorial follows.\n\n$roles"
        assertEquals("markdown", ChatMarkdownParser.detectFormat(tutorial))
        assertEquals(tutorial, ChatMarkdownParser.parse(tutorial).messages.single().content)
        assertEquals("rikkahub", ChatMarkdownParser.detectFormat("*Exported on 2030/06/12*\n\nuser: hello"))
    }

    @Test
    fun mistakenOverridesAndEmptyExportsPreserveTheirSource() {
        val source = "# Notes\n\n    a first indented line\n\nMeaningful text"
        for (format in listOf("rikkahub", "chatbox", "kelivo")) {
            assertEquals(source, ChatMarkdownParser.parse(source, format).messages.single().content)
        }
        assertEquals(source, ChatboxParser.parse(source).messages.single().content)
        assertEquals(source, KelivoParser.parse(source).messages.single().content)
        for (sourceOnly in listOf("**Assistant**:", "**user**:\n\n```\n```", "> 2030年6月12日 09:30:00 · reader")) {
            assertEquals(sourceOnly, ChatMarkdownParser.parse(sourceOnly).messages.single().content)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownFormatsRatherThanGuessing() {
        ChatMarkdownParser.parse("source", "unknown")
    }

    @Test
    fun inlineRoleFencesEstablishTheWholeTurnBoundary() {
        for (fence in listOf("```", "~~~~")) {
            val body = "${fence}markdown\nUser: a code example\n**Assistant**: still code\n$fence\n\nEnd"
            val source = "Assistant: $body\n\nUser: next turn"
            val messages = ChatMarkdownParser.parse(source).messages
            assertEquals(2, messages.size)
            assertEquals(body, messages[0].content)
            assertEquals("next turn", messages[1].content)
        }
        val unclosed = "```markdown\nUser: still inside\n\n---"
        assertEquals(unclosed, ChatMarkdownParser.parse("Assistant: $unclosed").messages.single().content)
    }

    @Test
    fun removesOnlyBlankBoundaryLinesAndPreservesCodeListsAndHardBreaks() {
        val bodies = listOf("    code\n    second line", "  - first\n    - nested", "text with hard break  ", "\tcode")
        for (body in bodies) {
            assertEquals(body, ChatMarkdownParser.parse("\n \n$body\n\n", "markdown").messages.single().content)
            assertEquals(body, reply("\n$body\n\n").content)
            assertEquals(body, ChatMarkdownParser.parse("**assistant**:\n\n```\n$body\n```", "chatbox").messages.single().content)
            assertEquals(body, ChatMarkdownParser.parse("> 2030年6月12日 09:30:00 · reader\n\n$body", "kelivo").messages.single().content)
        }
    }

    @Test
    fun indentedMetadataAndImagesRemainCode() {
        val body = "    > visible code\n    **Tool**: `example`\n    Input:\n    ![example]($data)\n\nAnswer"
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.images.isEmpty())
        assertEquals(listOf("text"), message.blocks.map { it.kind })
        val indentedRoles = "    User: a code example\n    Assistant: another"
        assertEquals(indentedRoles, reply(indentedRoles).content)
    }

    @Test
    fun nonembeddedImageAndLinkSyntaxIsNeverRewritten() {
        val body = listOf(
            "![logo](https://example.com/path_(one).png \"A logo title\")",
            "![logo](<https://example.com/a b.png> 'A title')",
            "![logo](https://example.com/a b.png)",
            "[link](https://example.com/a_(b) \"Link title\")",
            "![reference][image-id]",
            "[image-id]: https://example.com/image.png \"Reference title\""
        ).joinToString("\n\n")
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.images.isEmpty())
    }

    @Test
    fun imageSyntaxInLinkDestinationsAndTitlesIsLiteral() {
        val image = "![example]($data)"
        val body = "[link](https://example.com \"Documentation: $image\")\n\n[link](https://example.com/$image)"
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.images.isEmpty())
        val linkedImage = reply("[$image](https://example.com)")
        assertEquals(listOf(data), linkedImage.images)
        assertTrue(linkedImage.content.contains("[image:0]"))
    }

    @Test
    fun imageTitlesAndAngleDestinationsDoNotBecomePayload() {
        val raw = "iVBOR" + "A".repeat(120)
        val body = listOf(
            "![one]($data \"A title with (parentheses)\")",
            "![two](<$data> 'Another title')",
            "![three]($raw (Title))",
            "![escaped \\] label]($data \"escaped \\\" quote\")"
        ).joinToString("\n\n")
        val message = reply(body)
        assertEquals(listOf(data, data, "data:image/png;base64,$raw", data), message.images)
        assertFalse(message.content.contains("title", true))
        assertTrue(message.content.contains("[image:3]"))
    }

    @Test
    fun multilineEmbeddedImagesKeepTitlesOutOfTheirData() {
        val body = "![wrapped](data:image/png;base64,AAAA\nBBBB\nCCCC\n \"A multiline\ntitle\")"
        val message = reply(body)
        assertEquals(listOf("data:image/png;base64,AAAABBBBCCCC"), message.images)
        assertEquals("[image:0]", message.content)
    }

    @Test
    fun imagesInCodeAndEscapedImagesDoNotConsumeSlots() {
        val image = "![x]($data \"title\")"
        val body = listOf(
            "`$image`", "`` code ` $image ``", "\\$image", "    $image",
            "> ```markdown\n> $image\n> ```", "\\\\$image", image
        ).joinToString("\n\n")
        val message = ChatMarkdownParser.parse(body, "markdown").messages.single()
        assertEquals(listOf(data, data), message.images)
        assertTrue(message.content.contains("`$image`"))
        assertTrue(message.content.contains("\\$image"))
        assertTrue(message.content.contains("    $image"))
        assertTrue(message.content.contains("> $image"))
        assertTrue(message.content.contains("[image:1]"))
    }

    @Test
    fun malformedImagesAndDataDestinationsStayReadable() {
        val body = listOf(
            "![bad](data:image/png;base64,AAAA \"unfinished title)",
            "![bad](data:image/png;base64,not-a-base64-payload)",
            "![bad](data:image/png;base64,AAAA trailing words)",
            "![bad](data:text/plain;base64,AAAA)",
            "![bad](data:image/png;base64,AAAA"
        ).joinToString("\n\n")
        val message = reply(body)
        assertEquals(body, message.content)
        assertTrue(message.images.isEmpty())
    }

    @Test
    fun wrappedToolImageEndsAtTheActualNode() {
        val tool = "**Tool**: `draw`\nOutput:\n![result](data:image/png;base64,AAAA\nBBBB \"Tool title\")"
        val message = reply("$tool\n\nAnswer without punctuation")
        assertEquals(listOf("tool", "text"), message.blocks.map { it.kind })
        assertEquals(listOf("data:image/png;base64,AAAABBBB"), message.images)
        assertTrue(message.blocks.first().text.contains("[image:0]"))
        assertEquals("Answer without punctuation", message.content)
    }

    @Test
    fun missingAndMalformedToolSectionsRemainVisible() {
        val bodies = listOf(
            "**Tool**: `missing`\nInput:\nNo fence\n\nAnswer",
            "**Tool**: `missing`\nOutput:\nNo fence\n\nAnswer",
            "**Tool**: `missing`\nInput:\n```json\n{}\n```\nOutput:\n\nAnswer",
            "**Tool**: `missing`\nOutput:\n![x](data:image/png;base64,AAAA\n\nAnswer",
            "**Tool**: `missing`\nOutput:\n```text\nNever closed\n\nAnswer"
        )
        for (body in bodies) {
            val message = reply(body)
            assertEquals(body, message.content)
            assertTrue(message.blocks.none { it.kind == "tool" })
        }
    }

    @Test
    fun kelivoMissingThinkingFenceAndEmptyToolLabelStayVisible() {
        val prefix = "> 2030年6月12日 09:30:00 · reader\n\nQuestion\n\n---\n\n> 2030年6月12日 09:30:01 · model\n\n"
        for (body in listOf("**思考内容**\n\nNo fence\n\nAnswer", "[missing_tool]", "**思考内容**\n```text\nUnclosed\n\nAnswer")) {
            assertEquals(body, ChatMarkdownParser.parse(prefix + body).messages.last().content)
        }
    }
}
