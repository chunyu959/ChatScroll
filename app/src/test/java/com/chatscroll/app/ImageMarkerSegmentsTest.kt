package com.chatscroll.app

import com.chatscroll.app.ui.ImageMarkerSegment
import com.chatscroll.app.ui.imageMarkerSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMarkerSegmentsTest {
    private val images = listOf("first-image", "second-image", "third-image")

    @Test
    fun usesMarkerNumbersInTextOrder() {
        assertEquals(
            listOf(
                ImageMarkerSegment.Text("before "),
                ImageMarkerSegment.Image("third-image"),
                ImageMarkerSegment.Text(" between "),
                ImageMarkerSegment.Image("first-image"),
                ImageMarkerSegment.Text(" after")
            ),
            imageMarkerSegments("before [image:2] between [image:0] after", images)
        )
    }

    @Test
    fun rendersOnlyImagesReferencedByTheCurrentBlock() {
        assertEquals(
            listOf(ImageMarkerSegment.Image("second-image")),
            imageMarkerSegments("[image:1]", images)
        )
        assertEquals(
            listOf(ImageMarkerSegment.Text("another block")),
            imageMarkerSegments("another block", images)
        )
        assertEquals(emptyList<ImageMarkerSegment>(), imageMarkerSegments("", images))
    }

    @Test
    fun preservesMissingReferencesAmongValidImages() {
        assertEquals(
            listOf(
                ImageMarkerSegment.Text("[image:8] before "),
                ImageMarkerSegment.Image("second-image"),
                ImageMarkerSegment.Text(" after [image:9]")
            ),
            imageMarkerSegments("[image:8] before [image:1] after [image:9]", images)
        )
    }

    @Test
    fun preservesAllMarkersWhenNoImagesAreAvailable() {
        val text = "before [image:0] after [image:1]"
        assertEquals(
            listOf(ImageMarkerSegment.Text(text)),
            imageMarkerSegments(text, emptyList())
        )
    }

    @Test
    fun preservesMalformedAndOverflowingMarkersVerbatim() {
        val text = "[image:-1] [image:nope] [image:] [image:2147483648] [image:999999999999999999999] [image:0"
        assertEquals(
            listOf(ImageMarkerSegment.Text(text)),
            imageMarkerSegments(text, images)
        )
    }

    @Test
    fun handlesAdjacentAndRepeatedReferencesWithoutAddingOtherImages() {
        assertEquals(
            listOf(
                ImageMarkerSegment.Image("third-image"),
                ImageMarkerSegment.Image("third-image"),
                ImageMarkerSegment.Image("first-image")
            ),
            imageMarkerSegments("[image:2][image:2][image:0]", images)
        )
    }

    @Test
    fun resolvesMultiDigitNumbersAsImageIndices() {
        val manyImages = (0..12).map { "image-$it" }
        assertEquals(
            listOf(ImageMarkerSegment.Image("image-12"), ImageMarkerSegment.Image("image-10")),
            imageMarkerSegments("[image:12][image:10]", manyImages)
        )
    }

    @Test
    fun preservesCodeAndEscapedMarkersAlongsideRealImageReferences() {
        val prefix = """
            Intro `[image:0]` and \[image:1].

            ```kotlin
            [image:0]
            ```

            Following paragraph with **Markdown**.

        """.trimIndent()
        val suffix = "\n\nEnd with ``literal `[image:1]` text``."
        assertEquals(
            listOf(
                ImageMarkerSegment.Text(prefix),
                ImageMarkerSegment.Image("third-image"),
                ImageMarkerSegment.Text(suffix)
            ),
            imageMarkerSegments(prefix + "[image:2]" + suffix, images)
        )
    }

    @Test
    fun closesFencesOnlyWithMatchingCharacterAndSufficientLength() {
        val prefix = """
            ````text
            [image:0]
            ```
            [image:1]
            ~~~~
            [image:0]
            `````
            ~~~~text
            [image:1]
            ```
            [image:0]
            ~~~
            [image:1]
            ~~~~~
        """.trimIndent() + "\n"
        assertEquals(
            listOf(ImageMarkerSegment.Text(prefix), ImageMarkerSegment.Image("third-image")),
            imageMarkerSegments(prefix + "[image:2]", images)
        )
    }

    @Test
    fun preservesUnclosedFenceThroughEndOfText() {
        val suffix = "\n\n   ~~~~text\n[image:0]\n~~~\n[image:1]"
        assertEquals(
            listOf(ImageMarkerSegment.Image("third-image"), ImageMarkerSegment.Text(suffix)),
            imageMarkerSegments("[image:2]" + suffix, images)
        )
    }

    @Test
    fun honorsFenceIndentationAndRejectsTrailingTextOnClosingFence() {
        val prefix = "   ~~~\n[image:0]\n    ~~~\n[image:1]\n   ~~~ trailing\n[image:0]\n   ~~~\n"
        assertEquals(
            listOf(ImageMarkerSegment.Text(prefix), ImageMarkerSegment.Image("third-image")),
            imageMarkerSegments(prefix + "[image:2]", images)
        )
    }

    @Test
    fun inlineCodeUsesMatchingBacktickRunLengthsAndCanSpanLines() {
        val prefix = "``[image:0] ` [image:1]\n[image:0]`` and `[image:1]` then "
        assertEquals(
            listOf(ImageMarkerSegment.Text(prefix), ImageMarkerSegment.Image("third-image")),
            imageMarkerSegments(prefix + "[image:2]", images)
        )
    }

    @Test
    fun unmatchedOrEscapedBackticksDoNotHideRealMarkers() {
        assertEquals(
            listOf(ImageMarkerSegment.Text("`unmatched "), ImageMarkerSegment.Image("first-image")),
            imageMarkerSegments("`unmatched [image:0]", images)
        )
        assertEquals(
            listOf(
                ImageMarkerSegment.Text("\\`escaped "),
                ImageMarkerSegment.Image("second-image"),
                ImageMarkerSegment.Text("\\`")
            ),
            imageMarkerSegments("\\`escaped [image:1]\\`", images)
        )
    }

    @Test
    fun oddBackslashCountEscapesMarkersWhileEvenCountDoesNot() {
        val escaped = """\[image:0] \\\[image:1] """
        val unescaped = "\\\\"
        assertEquals(
            listOf(ImageMarkerSegment.Text(escaped + unescaped), ImageMarkerSegment.Image("third-image")),
            imageMarkerSegments(escaped + unescaped + "[image:2]", images)
        )
    }

    @Test
    fun preservesSurroundingWhitespaceAndLegacyDataUris() {
        val legacyImage = "![old](data:image/png;base64,AQID)"
        assertEquals(
            listOf(
                ImageMarkerSegment.Text("  $legacyImage\n\n"),
                ImageMarkerSegment.Image("first-image"),
                ImageMarkerSegment.Text("\n  after  ")
            ),
            imageMarkerSegments("  $legacyImage\n\n[image:0]\n  after  ", images)
        )
    }
}
