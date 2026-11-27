package org.breezyweather.sources

import breezyweather.domain.weather.model.AirQuality
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Date

/**
 * TODO: To be completed
 */
class CommonConverterTest {

    @Test
    fun airQualityUsesOnlyTheHourContainingNow() {
        val current = AirQuality(reportedAqi = 26)
        val future = AirQuality(reportedAqi = 100)
        val readings = mapOf(
            Date(7_200_000) to future,
            Date(5_400_000) to AirQuality(reportedAqi = -1),
            Date(3_600_000) to current
        )
        readings.currentHourAirQuality(3_600_000) shouldBe current
        readings.currentHourAirQuality(7_199_999) shouldBe current
        readings.currentHourAirQuality(3_599_999) shouldBe null
        readings.currentHourAirQuality(10_800_000) shouldBe null
    }

    @Test
    fun getWindDegreeTest() = runTest {
        getWindDegree(null) shouldBe null
        getWindDegree("E") shouldBe 90.0
        getWindDegree("SSO") shouldBe 202.5
        getWindDegree("VR") shouldBe -1.0
    }
}
