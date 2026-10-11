package com.chatscroll.app

import com.chatscroll.app.ui.normalizeMathDelimiters
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.inlineparser.MarkwonInlineParser
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Exports write inline math as `\(...\)` and display math as a `\[...\]` block,
 * while the renderer only treats a `$$` pair on its own line as a display formula
 * and has no inline dollar syntax at all. These check both the display rewrite and,
 * through the real parser, that an inline formula becomes a formula rather than text.
 */
class MathDelimiterTest {

    @Test
    fun rewritesMultilineDisplayMathAsOneBlock() {
        val input = """
            求根公式：

            \[
            x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}
            \]

            完毕
        """.trimIndent()
        val out = normalizeMathDelimiters(input)
        assertEquals(
            """
            求根公式：

            $$
            x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}
            $$

            完毕
            """.trimIndent(),
            out
        )
    }

    @Test
    fun keepsBackslashCommandsInsideTheFormula() {
        val input = """
            \[
            \begin{aligned}
            (a+b)^2&=a^2+2ab+b^2\\
            \end{aligned}
            \]
        """.trimIndent()
        val out = normalizeMathDelimiters(input)
        assertEquals(
            """
            $$
            \begin{aligned}
            (a+b)^2&=a^2+2ab+b^2\\
            \end{aligned}
            $$
            """.trimIndent(),
            out
        )
    }

    @Test
    fun leavesPricesAndEscapedBracketsAlone() {
        // A single dollar is a price, not math. A backslash before a bracket that
        // is not a matched formula delimiter must survive untouched.
        val input = """价格是 $5，转义括号 \( 没有闭合，方括号 \[ 也没有闭合"""
        assertEquals(input, normalizeMathDelimiters(input))
    }

    @Test
    fun doesNothingWhenThereIsNoBackslash() {
        val input = "普通文字，没有公式，也没有 $ 符号"
        assertEquals(input, normalizeMathDelimiters(input))
    }

/**
     * Parses the way the app does and checks the result is a formula, not text.
     *
     * This is the quadratic from the screen where inline math showed up as raw
     * `$ax^2+bx+c=0$`. Checking the text alone passed while the phone still showed
     * dollars, because `$...$` and `$$...$$` inside a sentence are both plain text.
     * Asserting on the parsed node is what actually guards the fix.
     */
    @Test
    fun inlineParenMathBecomesAFormula() {
        val input = """
            一元二次方程 \(ax^2+bx+c=0\)（\(a\neq 0\)）的求根公式：

            \[
            x=\frac{-b}{2a}
            \]

            判别式 \(\Delta=b^2-4ac\)，价格是 $5 不受影响
        """.trimIndent()

        val parser = Parser.builder()
            .inlineParserFactory(
                MarkwonInlineParser.factoryBuilderNoDefaults()
                    .apply {
                        addInlineProcessor(parenMathProcessor())
                        includeDefaults()
                    }
                    .build()
            )
            .customBlockParserFactory(JLatexMathBlockParserFactory())
            .build()

        val inline = ArrayList<String>()
        val display = ArrayList<String>()
        val text = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is JLatexMathNode -> inline.add(node.latex())
                is JLatexMathBlock -> display.add(node.latex().trim())
                is Text -> text.append(node.literal)
            }
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(parser.parse(normalizeMathDelimiters(input)))

        assertEquals(listOf("ax^2+bx+c=0", "a\\neq 0", "\\Delta=b^2-4ac"), inline)
        assertEquals(listOf("x=\\frac{-b}{2a}"), display)
        assertTrue(text.contains("一元二次方程"))
        assertTrue(text.contains("的求根公式"))
        assertTrue("a price must stay text", text.contains("\$5"))
        assertTrue("no delimiter left behind", !text.contains("\\(") && !text.contains("\\[") && !text.contains("\$\$"))
    }

    /** The inline processor is private, so the test builds its own of the same shape. */
    private fun parenMathProcessor(): io.noties.markwon.inlineparser.InlineProcessor =
        object : io.noties.markwon.inlineparser.InlineProcessor() {
            override fun specialCharacter(): Char = '\\'
            override fun parse(): Node? {
                val matched = match(Pattern.compile("\\\\\\((.+?)\\\\\\)")) ?: return null
                return JLatexMathNode().apply { latex(matched.substring(2, matched.length - 2)) }
            }
        }

    /** The block parser factory is package-private, so reach it the way the plugin does. */
    private fun JLatexMathBlockParserFactory(): org.commonmark.parser.block.BlockParserFactory {
        val cls = Class.forName("io.noties.markwon.ext.latex.JLatexMathBlockParser\$Factory")
        return cls.getDeclaredConstructor().newInstance() as org.commonmark.parser.block.BlockParserFactory
    }

    private fun renderTree(node: Node): String {
        val out = StringBuilder()
        fun walk(current: Node) {
            when (current) {
                is Text -> out.append("TEXT[").append(current.literal).append("]")
                is JLatexMathNode -> out.append("JLatexMathNode(").append(current.latex()).append(")")
                is JLatexMathBlock -> out.append("JLatexMathBlock(").append(current.latex().trim()).append(")")
            }
            var child = current.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }
        walk(node)
        return out.toString()
    }
}
