package org.breezyweather.sources.emb

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Base
import breezyweather.domain.weather.model.Weather
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.sources.completeCurrentFromHourlyData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Date
import java.util.concurrent.TimeUnit

class EmbReportTest {

    private val manila = Location(latitude = 14.59, longitude = 120.98, countryCode = "PH")
    private val report = Json { ignoreUnknownKeys = true }.decodeFromString<EmbReport>(
        """
        {
          "dataClock": "2026-11-26 18:00",
          "updatedAt": "2026-11-26T18:00:00.000Z",
          "fetchedAt": "2026-11-26T17:19:59.000Z",
          "stations": [
            {"name":"Navotas","aqi":97},
            {"name":"Manila","aqi":25,"pm25":25,"pm10":18}
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `uses nearest station index and actual fetch instant`() {
        val air = report.toAirQuality(manila, report.fetchedAt.time)
        air.current?.reportedAqi shouldBe 25
        air.current?.reportedStation shouldBe "Manila"
        air.current?.reportedClock shouldBe "2026-11-26 18:00"
        air.current?.pM25 shouldBe null
        air.current?.pM10 shouldBe null
        air.fetchedAt shouldBe report.fetchedAt
    }

    @Test
    fun `keeps a current station reading through refreshes without forecast data`() {
        val now = Date()
        val wrapper = report.copy(fetchedAt = now).toAirQuality(manila, now.time)
        val current = completeCurrentFromHourlyData(null, null, null, wrapper.current, manila)
        val weather = Weather(base = Base(airQualityUpdateTime = now), current = current)

        weather.toAirQualityWrapperList(now)?.current?.reportedAqi shouldBe 25
        val expired = weather.copy(base = Base(airQualityUpdateTime = Date(0)))
        expired.toAirQualityWrapperList(now) shouldBe null
    }

    @Test
    fun `preserves zero and skips unavailable and ungeolocated stations`() {
        val partial = report.copy(
            stations = listOf(EmbStation("New site", 200), EmbStation("Manila", null), EmbStation("Navotas", 0))
        )
        partial.toAirQuality(manila, report.fetchedAt.time).current?.reportedAqi shouldBe 0
    }

    @Test
    fun `rejects readings far from the requested location and expired snapshots`() {
        val cebu = Location(latitude = 10.32, longitude = 123.90, countryCode = "PH")
        assertThrows<InvalidOrIncompleteDataException> { report.toAirQuality(cebu, report.fetchedAt.time) }
        assertThrows<OutdatedServerDataException> {
            report.toAirQuality(manila, report.fetchedAt.time + TimeUnit.HOURS.toMillis(25))
        }
    }
}
