package com.chatscroll.app

import com.chatscroll.app.parser.ChatMarkdownParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in private fixtures. Only filenames, counts and line indices may reach failure output. */
class ExportCorpusTest {
    private data class Anchor(val line: Int, val headerLine: Int)
    private data class Header(val line: Int, val role: ChatMarkdownParser.Role)
    private data class Spec(
        val name: String,
        val messages: Int,
        val users: Int,
        val assistants: Int,
        val tools: Int,
        val anchors: List<Anchor> = emptyList(),
        val toolRanges: List<IntRange> = emptyList()
    )
    private data class Corpus(
        val spec: Spec,
        val lines: List<String>,
        val headers: List<Header>,
        val result: ChatMarkdownParser.Result
    )

    // These ranges were counted from the fixtures' closing Output fences, independently
    // of production parsing. The first line is the tool header; the last is its output end.
    private fun ranges(value: String): List<IntRange> = value.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { range ->
            val (first, last) = range.split('-').map(String::toInt)
            first..last
        }

    private val specs = listOf(
        Spec("CLAUDE（1）.md", 90, 45, 45, 0),
        Spec(
            "claude（2）.md", 63, 32, 31, 1,
            anchors = listOf(Anchor(840, 822)),
            toolRanges = ranges("826-838")
        ),
        Spec(
            "claude（3）.md", 93, 47, 46, 25,
            anchors = listOf(
                Anchor(840, 822), Anchor(6070, 6052), Anchor(6168, 6052),
                Anchor(6276, 6052), Anchor(6409, 6052), Anchor(6557, 6466),
                Anchor(6603, 6466), Anchor(6768, 6739), Anchor(6785, 6739),
                Anchor(6886, 6867)
            ),
            toolRanges = ranges(
                """
                826-838 6057-6068 6077-6088 6090-6101 6103-6114 6116-6127
                6129-6140 6142-6153 6155-6166 6237-6248 6250-6261 6263-6274
                6395-6407 6492-6503 6505-6516 6518-6529 6531-6542 6544-6555
                6564-6575 6577-6588 6590-6601 6742-6753 6755-6766 6771-6783 6872-6884
                """.trimIndent()
            )
        ),
        Spec(
            "claude（4）.md", 49, 25, 24, 2,
            anchors = listOf(Anchor(1373, 1350)),
            toolRanges = ranges("1354-1367 1522-1535")
        ),
        Spec(
            "claude（5）.md", 89, 46, 43, 52,
            anchors = listOf(
                Anchor(1615, 1548), Anchor(1786, 1707), Anchor(1901, 1864),
                Anchor(2031, 1985), Anchor(2100, 2071), Anchor(2177, 2153),
                Anchor(2284, 2224), Anchor(2378, 2359), Anchor(2687, 2552),
                Anchor(2748, 2715), Anchor(2824, 2797), Anchor(2923, 2887),
                Anchor(3086, 3067), Anchor(3175, 3154), Anchor(3389, 3309),
                Anchor(3627, 3583), Anchor(3696, 3675), Anchor(4043, 4019),
                Anchor(4299, 4141), Anchor(4399, 4362)
            ),
            toolRanges = ranges(
                """
                1553-1571 1573-1591 1600-1613 1716-1728 1730-1742 1744-1756
                1758-1770 1772-1784 1871-1884 1886-1899 1990-2001 2003-2014
                2016-2027 2081-2098 2162-2175 2239-2252 2254-2267 2269-2282
                2363-2376 2556-2573 2575-2592 2594-2611 2613-2630 2632-2649
                2651-2668 2672-2683 2727-2740 2804-2817 2891-2904 2906-2919
                3071-3084 3158-3171 3313-3331 3333-3351 3355-3368 3372-3385
                3587-3604 3606-3623 3679-3692 4026-4039 4145-4154 4158-4171
                4175-4188 4190-4203 4205-4218 4220-4233 4237-4250 4252-4265
                4267-4280 4282-4295 4367-4380 4382-4395
                """.trimIndent()
            )
        )
    )

    @Test
    fun privateExportsPreserveMessageBodiesToolEndsAndImages() {
        val configured = System.getProperty("chatscroll.corpusDir")
        assumeTrue(
            "Private export corpus skipped: set chatscroll.corpusDir to enable all five fixtures.",
            configured != null
        )
        assertTrue("chatscroll.corpusDir: empty configuration", !configured.isNullOrBlank())
        val directory = File(configured!!)
        // A configured corpus is mandatory in full; missing files must fail, never skip.
        specs.forEach { spec ->
            assertTrue("${spec.name}: required file missing", File(directory, spec.name).isFile)
        }
        val corpora = specs.map { spec ->
            val source = try {
                File(directory, spec.name).readText(Charsets.UTF_8)
            } catch (_: Exception) {
                throw AssertionError("${spec.name}: read failure")
            }
            val parsed = try {
                ChatMarkdownParser.parse(source)
            } catch (_: Exception) {
                // Do not attach an exception whose message might include fixture content.
                throw AssertionError("${spec.name}: parse failure")
            }
            val lines = source.lines()
            Corpus(spec, lines, headers(lines), parsed).also(::verifyCorpus)
        }

        val fourth = corpora[3]
        assertTrue(
            "${fourth.spec.name}: lines 1368-1372 blank count 5",
            fourth.lines.subList(1367, 1372).all(String::isBlank)
        )
        verifyToolImage(corpora[2], imageLine = 6780, headerLine = 6739, toolLine = 6771)
        verifyToolImage(corpora[2], imageLine = 6881, headerLine = 6867, toolLine = 6872)

        // Compare Message values only; the export title is intentionally not part of this check.
        val shorter = corpora[1]
        val longer = corpora[2]
        repeat(63) { index ->
            assertTrue(
                "${shorter.spec.name}/${longer.spec.name}: overlap header lines " +
                    "${shorter.headers[index].line}/${longer.headers[index].line}",
                shorter.result.messages[index] == longer.result.messages[index]
            )
        }
    }

    private fun headers(lines: List<String>): List<Header> = lines.mapIndexedNotNull { index, line ->
        // Exact fixture role labels, not production format detection or segmentation.
        val role = when (line.trim()) {
            "**User**:", "**user**:" -> ChatMarkdownParser.Role.USER
            "**Assistant**:", "**assistant**:" -> ChatMarkdownParser.Role.ASSISTANT
            else -> return@mapIndexedNotNull null
        }
        Header(index + 1, role)
    }

    private fun verifyCorpus(corpus: Corpus) {
        val (spec, lines, headers, result) = corpus
        assertTrue("${spec.name}: source header count ${headers.size}/${spec.messages}", headers.size == spec.messages)
        assertTrue("${spec.name}: message count ${result.messages.size}/${spec.messages}", result.messages.size == spec.messages)
        val users = result.messages.count { it.role == ChatMarkdownParser.Role.USER }
        val assistants = result.messages.count { it.role == ChatMarkdownParser.Role.ASSISTANT }
        assertTrue("${spec.name}: user count $users/${spec.users}", users == spec.users)
        assertTrue("${spec.name}: assistant count $assistants/${spec.assistants}", assistants == spec.assistants)
        val toolCount = result.messages.sumOf { message -> message.blocks.count { it.kind == "tool" } }
        assertTrue("${spec.name}: tool count $toolCount/${spec.tools}", toolCount == spec.tools)
        assertTrue("${spec.name}: tool range count ${spec.toolRanges.size}/${spec.tools}", spec.toolRanges.size == spec.tools)

        result.messages.forEachIndexed { index, message ->
            val label = "${spec.name}: header line ${headers[index].line}"
            assertTrue("$label role", headers[index].role == message.role)
            val text = message.blocks.filter { it.kind == "text" }.joinToString("\n\n") { it.text }
            assertTrue("$label content/text blocks", message.content == text)
            verifyImages(message, label)
        }
        spec.anchors.forEach { anchor ->
            val messageIndex = messageIndex(corpus, anchor.headerLine)
            assertTrue(
                "${spec.name}: anchor line ${anchor.line} header line ${anchor.headerLine}",
                headers.lastOrNull { it.line < anchor.line }?.line == anchor.headerLine
            )
            val expectedLine = lines.getOrNull(anchor.line - 1)?.trim()
            assertTrue("${spec.name}: anchor line ${anchor.line} empty", !expectedLine.isNullOrBlank())
            val message = result.messages[messageIndex]
            assertTrue(
                "${spec.name}: anchor line ${anchor.line} text block",
                message.blocks.filter { it.kind == "text" }
                    .any { block -> block.text.lineSequence().any { it.trim() == expectedLine } }
            )
        }
        verifyTools(corpus)
    }

    private fun messageIndex(corpus: Corpus, headerLine: Int): Int {
        val index = corpus.headers.indexOfFirst { it.line == headerLine }
        assertTrue("${corpus.spec.name}: header line $headerLine missing", index >= 0)
        return index
    }

    private fun verifyTools(corpus: Corpus) {
        val toolHeader = Regex("\\*\\*Tool\\*\\*:\\s*`([^`]*)`")
        corpus.headers.forEachIndexed { index, header ->
            val expected = corpus.spec.toolRanges.filter { range ->
                corpus.headers.lastOrNull { it.line < range.first }?.line == header.line
            }
            val message = corpus.result.messages[index]
            val actual = message.blocks.filter { it.kind == "tool" }
            val label = "${corpus.spec.name}: header line ${header.line}"
            assertTrue("$label tool count ${actual.size}/${expected.size}", actual.size == expected.size)
            expected.forEachIndexed { toolIndex, range ->
                val location = "${corpus.spec.name}: tool lines ${range.first}-${range.last}"
                assertTrue("$location bounds", range.first > header.line && range.last <= corpus.lines.size)
                val name = toolHeader.matchEntire(corpus.lines[range.first - 1].trim())?.groupValues?.get(1)
                assertTrue("$location header", name != null)
                assertTrue("$location name", actual[toolIndex].name == name)
                assertTrue("$location output end", corpus.lines[range.last - 1].trim() == "```")
                val source = corpus.lines.subList(range.first, range.last).joinToString("\n").trim()
                val expectedText = normalizeToolImages(source, message, location)
                // Boolean comparison prevents JUnit from printing private expected/actual payloads.
                assertTrue("$location exact tool extent", actual[toolIndex].text == expectedText)
            }
        }
    }

    private val imageMarkup = Regex("!\\[[^]]*]\\((data:[^)]*)\\)", RegexOption.IGNORE_CASE)
    private val imageMarker = Regex("\\[image:(-?\\d+)]")
    private val rawDataImage = Regex("data:[^\\s,)]*;base64,", RegexOption.IGNORE_CASE)
    private val rawBase64 = Regex("[A-Za-z0-9+/]{100,}={0,2}")
    private val whitespace = Regex("\\s+")

    private fun normalizeToolImages(
        source: String,
        message: ChatMarkdownParser.Message,
        location: String
    ): String = mapOutsideFences(source) { line ->
        imageMarkup.replace(line) { match ->
            val image = whitespace.replace(match.groupValues[1], "")
            val index = message.images.indexOf(image)
            assertTrue("$location image index", index >= 0)
            "\n\n[image:$index]\n\n"
        }
    }.trim()

    private fun verifyToolImage(corpus: Corpus, imageLine: Int, headerLine: Int, toolLine: Int) {
        val label = "${corpus.spec.name}: image line $imageLine header line $headerLine tool line $toolLine"
        val message = corpus.result.messages[messageIndex(corpus, headerLine)]
        val source = imageMarkup.find(corpus.lines[imageLine - 1])?.groupValues?.get(1)
        assertTrue("$label source", source != null)
        val imageIndex = message.images.indexOf(whitespace.replace(source!!, ""))
        assertTrue("$label image index", imageIndex >= 0)
        val marker = "[image:$imageIndex]"
        val ranges = corpus.spec.toolRanges.filter { range ->
            corpus.headers.lastOrNull { it.line < range.first }?.line == headerLine
        }
        val toolIndex = ranges.indexOfFirst { it.first == toolLine && imageLine in it }
        assertTrue("$label range", toolIndex >= 0)
        val tools = message.blocks.filter { it.kind == "tool" }
        assertTrue("$label tool marker", tools[toolIndex].text.contains(marker))
        assertTrue("$label visible marker", message.blocks.filter { it.kind == "text" }.none { it.text.contains(marker) })
        assertTrue("$label content marker", !message.content.contains(marker))
    }

    private fun verifyImages(message: ChatMarkdownParser.Message, label: String) {
        val referenced = mutableSetOf<Int>()
        (message.blocks.map { it.text } + message.content).forEach { text ->
            imageMarker.findAll(text).forEach { match ->
                val index = match.groupValues[1].toIntOrNull()
                assertTrue("$label image marker bounds", index != null && index in message.images.indices)
                referenced.add(index!!)
            }
            mapOutsideFences(text) { line ->
                assertTrue("$label raw data image", !rawDataImage.containsMatchIn(line))
                assertTrue("$label raw base64", !rawBase64.containsMatchIn(line))
                line
            }
        }
        assertTrue("$label referenced image count ${referenced.size}/${message.images.size}", referenced == message.images.indices.toSet())
    }

    /** Minimal independent fence tracking, solely to exempt literal code examples. */
    private fun mapOutsideFences(text: String, transform: (String) -> String): String {
        var fence = ""
        return text.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val marker = trimmed.takeWhile { it == trimmed.firstOrNull() }
            if (fence.isNotEmpty()) {
                if (trimmed.trimEnd().all { it == fence[0] } && trimmed.trimEnd().length >= fence.length) {
                    fence = ""
                }
                line
            } else if (marker.length >= 3 && (marker[0] == '`' || marker[0] == '~')) {
                fence = marker
                line
            } else {
                transform(line)
            }
        }
    }
}
