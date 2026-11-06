package com.chatscroll.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.chatscroll.app.ui.ChatMarkwon
import com.chatscroll.app.ui.ReaderPreferences
import com.chatscroll.app.ui.normalizeMathDelimiters
import kotlin.math.abs

/** Framework-only runner. All waits run on Instrumentation's worker, never the UI thread. */
class RenderingRegressionInstrumentation : Instrumentation() {
    private lateinit var fixture: RenderingRegressionActivity
    private val expected = mutableMapOf<Pair<Int, Int>, String>()
    private var passed = 0
    private var step = "launch"
    private var invalidDraw: String? = null // Accessed only on main; retain the earliest broken draw.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (invalidDraw == null) textViews(fixture.window.decorView).firstOrNull { view ->
            val layout = view.layout
            view.text.isNotEmpty() && !view.isLayoutRequested && (view.measuredWidth <= 0 || view.measuredHeight <= 0 ||
                layout == null || layout.width <= 0 ||
                layout.width > view.measuredWidth - view.compoundPaddingLeft - view.compoundPaddingRight ||
                view.measuredHeight < layout.height + view.compoundPaddingTop + view.compoundPaddingBottom)
        }?.let { view -> invalidDraw = "$step: invalid native pre-draw ${view.measuredWidth}x${view.measuredHeight}, layout=${view.layout?.width}x${view.layout?.height}" }
        true
    }

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val failures = arrayListOf<String>()
        try {
            fixture = startActivitySync(Intent(targetContext, RenderingRegressionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RenderingRegressionActivity
            main { fixture.window.decorView.viewTreeObserver.addOnPreDrawListener(preDraw) }
            checkpoint("initial 15sp / 1.2", 0, 0)
            // Exercise fresh lazy rows before any query, typography change, or tap can repair layout.
            main { fixture.scrollTo(40) }; checkpoint("first far scroll", 40, 0)
            main { fixture.scrollTo(0) }; checkpoint("scroll back", 0, 0)
            main { fixture.updateSourceAsync() }; checkpoint("async source replacement", 0, 1)
            main { fixture.preferences = ReaderPreferences(18f, 1.6f) }
            checkpoint("18sp / 1.6", 0, 1)
            main { fixture.preferences = ReaderPreferences(18f, 1.2f) }
            checkpoint("spacing only", 0, 1)
            main { fixture.preferences = ReaderPreferences(15f, 1.2f) }
            val beforeSearch = checkpoint("return to 15sp / 1.2", 0, 1)
            main { fixture.query = "English" }
            checkpoint("query preserves native view", 0, 1, beforeSearch, highlighted = true)
        } catch (failure: Throwable) {
            failures += "$step: ${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            if (::fixture.isInitialized) runCatching {
                main {
                    fixture.window.decorView.viewTreeObserver.removeOnPreDrawListener(preDraw)
                    fixture.finishAndRemoveTask()
                }
                val deadline = SystemClock.uptimeMillis() + 3000
                while (!main { fixture.isDestroyed } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(16)
                check(main { fixture.isDestroyed }) { "fixture was not destroyed" }
            }.onFailure { failures += "cleanup: ${it.message}" }
            finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
                putInt("passed", passed)
                putInt("failed", failures.size)
                putStringArrayList("failureMessages", failures)
                putString("stream", "Rendering regression: $passed passed, ${failures.size} failed\n" + failures.joinToString("\n"))
            })
        }
    }

    private data class Sample(val view: TextView, val text: String, val layoutText: String?,
        val width: Int, val height: Int, val layoutWidth: Int, val layoutHeight: Int,
        val horizontalPadding: Int, val verticalPadding: Int, val textSize: Float,
        val spacing: Float, val pendingLayout: Boolean, val highlighted: Boolean)

    private fun checkpoint(label: String, index: Int, revision: Int,
        identity: TextView? = null, highlighted: Boolean = false): TextView {
        step = label
        val marker = RenderingRegressionActivity.marker(index, revision)
        val requested = main { fixture.preferences }
        val requestedSize = main { requested.fontSizeSp * fixture.resources.displayMetrics.scaledDensity }
        val deadline = SystemClock.uptimeMillis() + 4000
        var previous: List<Sample>? = null
        var last = "no visible native text"
        while (SystemClock.uptimeMillis() < deadline) {
            val samples = main {
                check(invalidDraw == null) { invalidDraw.orEmpty() }
                if (fixture.listState.isScrollInProgress || fixture.listState.firstVisibleItemIndex != index) emptyList()
                else textViews(fixture.window.decorView).map { view ->
                    val text = view.text
                    val painted = (text as? Spanned)?.let { spans ->
                        spans.getSpans(0, spans.length, BackgroundColorSpan::class.java).any {
                            spans.subSequence(spans.getSpanStart(it), spans.getSpanEnd(it)).toString() == "English"
                        }
                    } == true
                    Sample(view, text.toString(), view.layout?.text?.toString(), view.measuredWidth,
                        view.measuredHeight, view.layout?.width ?: 0, view.layout?.height ?: 0,
                        view.compoundPaddingLeft + view.compoundPaddingRight,
                        view.compoundPaddingTop + view.compoundPaddingBottom, view.textSize,
                        view.lineSpacingMultiplier, view.isLayoutRequested, painted)
                }
            }
            val target = samples.firstOrNull { it.text.startsWith(marker) }
            // Observe the first stable native measurements, not eventual successful measurements.
            if (target != null && target.highlighted == highlighted && samples == previous &&
                samples.none { it.pendingLayout } && samples.all {
                    abs(it.textSize - requestedSize) < 0.5f && abs(it.spacing - requested.lineHeightMultiplier) < 0.01f
                }) {
                main {
                    val preferences = fixture.preferences
                    val expectedSize = preferences.fontSizeSp * fixture.resources.displayMetrics.scaledDensity
                    samples.forEach { sample ->
                        val match = Regex("^REGRESSION_(\\d+)_V(\\d+)").find(sample.text)
                            ?: error("native view has no row marker")
                        val row = match.groupValues[1].toInt()
                        check(match.groupValues[2].toInt() == revision) { "row $row has stale source" }
                        val complete = expected.getOrPut(row to revision) {
                            ChatMarkwon.build(targetContext).toMarkdown(normalizeMathDelimiters(
                                RenderingRegressionActivity.source(row, revision))).toString()
                        }
                        check(complete.length > 450 && sample.text == complete && sample.layoutText == complete) {
                            "row $row incomplete text: ${sample.text.length}/${complete.length} characters"
                        }
                        check(sample.width > 0 && sample.height > 0 && sample.layoutWidth > 0 &&
                            sample.layoutWidth <= sample.width - sample.horizontalPadding) {
                            "row $row invalid width: native=${sample.width}, layout=${sample.layoutWidth}"
                        }
                        // Full native size, never getGlobalVisibleRect().height: tall rows may be clipped by the viewport.
                        check(sample.layoutHeight > 0 && sample.height >= sample.layoutHeight + sample.verticalPadding) {
                            "row $row truncated height: native=${sample.height}, layout=${sample.layoutHeight}, padding=${sample.verticalPadding}"
                        }
                        check(abs(sample.textSize - expectedSize) < 0.5f && abs(sample.spacing - preferences.lineHeightMultiplier) < 0.01f) {
                            "row $row stale typography: ${sample.textSize}px / ${sample.spacing}"
                        }
                    }
                    check(identity == null || target.view === identity) { "query recreated the TextView" }
                }
                passed++
                return target.view
            }
            last = "${samples.size} visible views, target=${target != null}, pending=${samples.any { it.pendingLayout }}"
            previous = samples
            SystemClock.sleep(16)
        }
        error("timed out awaiting stable $marker ($last)")
    }

    private fun textViews(root: View): List<TextView> = buildList {
        if (root is TextView && root.isShown && root.getGlobalVisibleRect(Rect())) add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(textViews(root.getChildAt(i)))
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
