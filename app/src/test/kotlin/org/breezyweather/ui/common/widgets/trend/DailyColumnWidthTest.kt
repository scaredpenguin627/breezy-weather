package org.breezyweather.ui.common.widgets.trend

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DailyColumnWidthTest {
    @Test
    fun shortForecastFillsTheWholeAvailableWidth() {
        val widths = (0 until 5).map { dailyColumnWidth(996, 5, it, 176) }
        widths shouldBe listOf(200, 199, 199, 199, 199)
        dailyColumnWidth(996, 1, 0, 176) shouldBe 996
    }

    @Test
    fun longForecastsAndLargeFontsRetainTheirScrollableMinimum() {
        dailyColumnWidth(996, 10, 0, 176) shouldBe 176
        dailyColumnWidth(996, 5, 4, 220) shouldBe 220
    }

    @Test
    fun handlesLayoutBeforeTheViewportOrForecastIsAvailable() {
        dailyColumnWidth(0, 5, 0, 176) shouldBe 176
        dailyColumnWidth(996, 0, -1, 176) shouldBe 176
    }
}
