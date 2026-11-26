package org.breezyweather.sources.emb

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.model.Base
import breezyweather.domain.weather.model.Current
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.Hourly
import breezyweather.domain.weather.model.Weather
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.sources.completeCurrentFromHourlyData
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
        assertThrows(InvalidOrIncompleteDataException::class.java) {
            report.toAirQuality(cebu, report.fetchedAt.time)
        }
        assertThrows(OutdatedServerDataException::class.java) {
            report.toAirQuality(manila, report.fetchedAt.time + TimeUnit.HOURS.toMillis(25))
        }
    }

    @Test
    fun `leaving station coverage clears air quality even for a small GPS move with cached weather`() {
        val now = Date()
        val beforeMove = Location(
            latitude = 14.294,
            longitude = 121.0337,
            countryCode = "PH",
            isCurrentPosition = true,
            airQualitySource = EmbServiceStub.ID
        )
        val reading = report.copy(stations = listOf(EmbStation("Muntinlupa (Bilibid)", 25)))
            .toAirQuality(beforeMove, report.fetchedAt.time).current
        val weather = Weather(
            base = Base(forecastUpdateTime = now, airQualityUpdateTime = now),
            current = Current(weatherText = "Cloudy", airQuality = reading),
            dailyForecast = listOf(Daily(date = now, airQuality = reading)),
            hourlyForecast = listOf(Hourly(date = now, weatherText = "Rain", airQuality = reading))
        )
        val moved = beforeMove.copy(latitude = 14.293, weather = weather)

        beforeMove.isOutsideEmbAirQualityCoverage shouldBe false
        moved.isOutsideEmbAirQualityCoverage shouldBe true
        moved.formattedId shouldBe beforeMove.formattedId
        val cleared = moved.withoutUncoveredEmbAirQuality()
        cleared.airQualitySource shouldBe EmbServiceStub.ID
        cleared.weather?.current shouldBe Current(weatherText = "Cloudy")
        cleared.weather?.base?.airQualityUpdateTime shouldBe null
        cleared.weather?.base?.forecastUpdateTime shouldBe now
        cleared.weather?.dailyForecast shouldBe listOf(Daily(date = now))
        cleared.weather?.hourlyForecast shouldBe listOf(Hourly(date = now, weatherText = "Rain"))
        cleared.weather?.toAirQualityWrapperList(now) shouldBe null
        (cleared.withoutUncoveredEmbAirQuality() === cleared) shouldBe true
    }

    @Test
    fun `station coverage is independent of whether the feed has a reading`() {
        val covered = manila.copy(airQualitySource = EmbServiceStub.ID, weather = Weather())
        val emptyReport = report.copy(stations = listOf(EmbStation("Manila")))

        covered.isOutsideEmbAirQualityCoverage shouldBe false
        (covered.withoutUncoveredEmbAirQuality() === covered) shouldBe true
        assertThrows(InvalidOrIncompleteDataException::class.java) {
            emptyReport.toAirQuality(covered, report.fetchedAt.time)
        }
    }

    @Test
    fun `empty cache placeholders do not trigger repeated clearing outside coverage`() {
        val date = Date()
        val emptyAirQuality = AirQuality()
        val boracay = Location(
            latitude = 11.98013,
            longitude = 121.91932,
            countryCode = "PH",
            airQualitySource = EmbServiceStub.ID,
            weather = Weather(
                current = Current(airQuality = emptyAirQuality),
                dailyForecast = listOf(Daily(date = date, airQuality = emptyAirQuality)),
                hourlyForecast = listOf(Hourly(date = date, airQuality = emptyAirQuality))
            )
        )

        (boracay.withoutUncoveredEmbAirQuality() === boracay) shouldBe true
    }

    @Test
    fun `only a located place with EMB selected gets the no-station state`() {
        val boracay = Location(latitude = 11.98013, longitude = 121.91932, countryCode = "PH")

        boracay.copy(airQualitySource = EmbServiceStub.ID).isOutsideEmbAirQualityCoverage shouldBe true
        boracay.copy(airQualitySource = "openmeteo").isOutsideEmbAirQualityCoverage shouldBe false
        boracay.isOutsideEmbAirQualityCoverage shouldBe false
        Location(isCurrentPosition = true, airQualitySource = EmbServiceStub.ID)
            .isOutsideEmbAirQualityCoverage shouldBe false
    }
}
