package org.breezyweather.sources.panahon

import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.Wind
import breezyweather.domain.weather.wrappers.CurrentWrapper
import breezyweather.domain.weather.wrappers.DailyWrapper
import breezyweather.domain.weather.wrappers.HalfDayWrapper
import breezyweather.domain.weather.wrappers.HourlyWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

class PanahonWindTest {
    private val midnight = Instant.parse("2026-11-26T16:00:00Z") // 00:00 in Manila
    private val manilaClock = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Manila"))

    private fun at(hour: Int): Date = Date.from(midnight.plusSeconds(hour * 3600L))

    private fun point(hour: Int, value: Double?, minute: Int = 0): PanahonWindPoint {
        val time = manilaClock.format(at(hour).toInstant().plusSeconds(minute * 60L))
        return PanahonWindPoint(time, value?.let { JsonPrimitive(it) })
    }

    @Test
    fun addsOnlyReportedWindTimesInsideNativeDaysAndComputesPeaksThroughFinalNight() {
        val native = WeatherWrapper(
            dailyForecast = (0..4).map { DailyWrapper(at(it * 24)) },
            hourlyForecast = listOf(HourlyWrapper(at(6)), HourlyWrapper(Date(at(17).time + 1))),
            current = CurrentWrapper(weatherText = "Clear skies"),
            alertList = emptyList()
        )
        val hours = listOf(5, 6, 17, 18, 29, 30, 54, 78, 102, 125, 126)
        val speeds = listOf(100.0, 0.0, 9.0, 4.0, 10.0, 7.0, 3.0, 2.0, 1.0, 6.0, 55.0)
        val report = PanahonWindReport(
            true,
            "ecmwf",
            PanahonWindData(
                hours.zip(speeds).map { (h, speed) -> point(h, speed) },
                hours.reversed().map { point(it, it.toDouble()) }
            )
        )
        val result = native.withForecastWind(report, midnight)
        result.hourlyForecast?.map { it.date } shouldBe native.hourlyForecast?.map { it.date }
        result.hourlyForecast?.map { it.wind?.speed?.inMetersPerSecond } shouldBe listOf(0.0, null)
        result.forecastWindSamples.keys shouldBe hours.filter { it < 120 }.map(::at).toSet()
        result.forecastWindSamples[at(78)] shouldBe Wind(78.0, 2.metersPerSecond)
        val days = result.dailyForecast!!
        days.map { it.day?.wind?.speed?.inMetersPerSecond } shouldBe listOf(9.0, 7.0, 3.0, 2.0, 1.0)
        days.map { it.night?.wind?.speed?.inMetersPerSecond } shouldBe listOf(10.0, null, null, null, 6.0)
        days.first().night?.wind?.degree shouldBe 29.0
        days.last().night?.wind?.degree shouldBe 125.0
        result.current shouldBe native.current
        result.alertList shouldBe native.alertList
    }

    @Test
    fun retainsNativeWindAndFiltersInvalidMeasurementsBeforeSelectingPeak() {
        val nativeHourlyWind = Wind(170.0, 13.metersPerSecond, 18.metersPerSecond)
        val partialNativeWind = Wind(degree = 25.0, gusts = 11.metersPerSecond)
        val nativeDay = HalfDayWrapper(wind = Wind(140.0, 3.metersPerSecond))
        val native = WeatherWrapper(
            dailyForecast = listOf(DailyWrapper(at(0), night = nativeDay)),
            hourlyForecast = listOf(
                HourlyWrapper(at(6), wind = nativeHourlyWind),
                HourlyWrapper(at(7), wind = partialNativeWind),
                HourlyWrapper(at(8)),
                HourlyWrapper(at(9)),
                HourlyWrapper(at(10))
            )
        )
        val hours = listOf(6, 7, 8, 9, 10, 18)
        val report = PanahonWindReport(
            true,
            "ecmwf",
            PanahonWindData(
                hours.zip(listOf(9.0, 8.0, 5.0, 151.0, null, 20.0)).map { (h, v) -> point(h, v) },
                hours.zip(listOf(110.0, 22.0, 361.0, 100.0, null, 150.0)).map { (h, v) -> point(h, v) }
            )
        )
        val result = native.withForecastWind(report, midnight)
        result.hourlyForecast?.map { it.wind } shouldBe listOf(
            nativeHourlyWind,
            partialNativeWind.copy(speed = 8.metersPerSecond),
            Wind(speed = 5.metersPerSecond),
            null,
            null
        )
        result.dailyForecast?.single()?.day?.wind shouldBe nativeHourlyWind
        result.dailyForecast?.single()?.night shouldBe nativeDay
    }

    @Test
    fun keepsSparseSamplesAtTheirReportedTimesIncludingPartialHours() {
        val native = WeatherWrapper(
            dailyForecast = listOf(DailyWrapper(at(0))),
            hourlyForecast = listOf(HourlyWrapper(at(12)), HourlyWrapper(at(13)))
        )
        val report = PanahonWindReport(
            true,
            "ecmwf",
            PanahonWindData(
                speeds = listOf(point(12, 4.1, 15), point(17, 0.0), point(18, -2.0)),
                directions = listOf(point(12, -1.0, 15), point(17, null), point(18, 360.0))
            )
        )
        val result = native.withForecastWind(report, midnight)
        result.hourlyForecast shouldBe native.hourlyForecast
        result.forecastWindSamples.size shouldBe 2
        result.forecastWindSamples[at(17)] shouldBe Wind(speed = 0.0.metersPerSecond)
        result.dailyForecast?.single()?.day?.wind shouldBe Wind(359.0, 4.1.metersPerSecond)
        result.dailyForecast?.single()?.night?.wind shouldBe null
    }

    @Test
    fun handlesOfficialClockAndSignedBearingsAndOnlyAcceptsSuccessfulEcmwfReports() {
        val report = Json.decodeFromString<PanahonWindReport>(
            """
            {
              "success": true, "model": "ecmwf",
              "data": {
                "wind_speed": [null, {"value":100}, {"forecast_at":"2026-11-27 00:00:00","value":1.5}],
                "wind_direction": [{"forecast_at":"2026-11-27 00:00:00","value":-90}]
              }
            }
            """.trimIndent()
        )
        val native = WeatherWrapper(hourlyForecast = listOf(HourlyWrapper(at(0))))
        native.withForecastWind(report, midnight).hourlyForecast?.single()?.wind shouldBe
            Wind(270.0, 1.5.metersPerSecond)
        native.withForecastWind(report.copy(success = false), midnight) shouldBe native
        native.withForecastWind(report.copy(model = "atmo"), midnight) shouldBe native
        native.withForecastWind(report, midnight.minusSeconds(145 * 3600L)) shouldBe native
        val failed = native.copy(failedFeatures = mapOf(SourceFeature.FORECAST to IOException()))
        failed.withForecastWind(report, midnight) shouldBe failed
    }
}
