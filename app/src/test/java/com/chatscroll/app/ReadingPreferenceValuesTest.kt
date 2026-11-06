package com.chatscroll.app

import com.chatscroll.app.data.ReadingPreferenceValues
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingPreferenceValuesTest {
    @Test fun themeFallsBackToSystemForUnsupportedValues() {
        assertEquals("system", ReadingPreferenceValues.themeMode(null))
        assertEquals("system", ReadingPreferenceValues.themeMode("unknown"))
        assertEquals("dark", ReadingPreferenceValues.themeMode("dark"))
        assertEquals("light", ReadingPreferenceValues.themeMode("light"))
    }

    @Test fun fontSizeClampsAndRejectsNonFiniteValues() {
        assertEquals(14f, ReadingPreferenceValues.fontSize(-2f), 0f)
        assertEquals(24f, ReadingPreferenceValues.fontSize(40f), 0f)
        assertEquals(16f, ReadingPreferenceValues.fontSize(Float.NaN), 0f)
        assertEquals(16f, ReadingPreferenceValues.fontSize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(18f, ReadingPreferenceValues.fontSize(18f), 0f)
    }

    @Test fun lineHeightClampsAndRejectsNonFiniteValues() {
        assertEquals(1.2f, ReadingPreferenceValues.lineHeight(0f), 0f)
        assertEquals(2f, ReadingPreferenceValues.lineHeight(3f), 0f)
        assertEquals(1.55f, ReadingPreferenceValues.lineHeight(Float.NaN), 0f)
        assertEquals(1.55f, ReadingPreferenceValues.lineHeight(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1.75f, ReadingPreferenceValues.lineHeight(1.75f), 0f)
    }

    @Test fun secondsCommitNeverKeepsEmptyOrZero() {
        assertEquals(3, ReadingPreferenceValues.secondsText("", 3))
        assertEquals(1, ReadingPreferenceValues.secondsText("0", 3))
        assertEquals(1, ReadingPreferenceValues.secondsText("-2", 3))
        assertEquals(3, ReadingPreferenceValues.secondsText("999999999999", 3))
        assertEquals(1, ReadingPreferenceValues.secondsText("", 0))
        assertEquals(12, ReadingPreferenceValues.secondsText("0012", 3))
    }
}
