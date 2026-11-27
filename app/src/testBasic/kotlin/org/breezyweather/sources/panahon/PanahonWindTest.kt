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
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.Date

class PanahonWindTest {
    private val midnight = Instant.parse("2026-11-26T16:00:00Z") // 00:00 in Manila
    private val units = OpenMeteoWindUnits("unixtime", "m/s", "°")

    private fun at(hour: Int): Date = Date.from(midnight.plusSeconds(hour * 3600L))

    @Test
    fun fillsOnlyMatchingNativeHoursAndComputesAllFiveDaysThroughFinalNight() {
        val native = WeatherWrapper(
            dailyForecast = (0..4).map { DailyWrapper(at(it * 24)) },
            hourlyForecast = listOf(HourlyWrapper(at(6)), HourlyWrapper(Date(at(17).time + 1))),
            current = CurrentWrapper(weatherText = "Clear skies"),
            alertList = emptyList()
        )
        val hours = listOf(5, 6, 17, 18, 29, 30, 54, 78, 102, 125, 126)
        val speeds = listOf(100.0, 0.0, 9.0, 4.0, 10.0, 7.0, 3.0, 2.0, 1.0, 6.0, 55.0)
        val report = OpenMeteoWindReport(
            OpenMeteoWindHourly(
                hours.map { at(it).toInstant().epochSecond },
                speeds,
                hours.map { it.toDouble() }
            ),
            units
        )
        val result = native.withForecastWind(report)
        result.hourlyForecast?.map { it.date } shouldBe native.hourlyForecast?.map { it.date }
        result.hourlyForecast?.map { it.wind?.speed?.inMetersPerSecond } shouldBe listOf(0.0, null)
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
        val report = OpenMeteoWindReport(
            OpenMeteoWindHourly(
                listOf(6, 7, 8, 9, 10, 18).map { at(it).toInstant().epochSecond },
                listOf(9.0, 8.0, 5.0, 151.0, null, 20.0),
                listOf(110.0, 22.0, 361.0, 100.0, null, 150.0)
            ),
            units
        )
        val result = native.withForecastWind(report)
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
    fun verifiesWireUnitsAndNeverReplacesFailedNativeForecast() {
        val report = Json.decodeFromString<OpenMeteoWindReport>(
            """
            {
              "hourly_units": {"time":"unixtime","wind_speed_10m":"m/s","wind_direction_10m":"°"},
              "hourly": {"time":[${midnight.epochSecond}],"wind_speed_10m":[1.5],"wind_direction_10m":[90]}
            }
            """.trimIndent()
        )
        val native = WeatherWrapper(hourlyForecast = listOf(HourlyWrapper(at(0))))
        native.withForecastWind(report).hourlyForecast?.single()?.wind shouldBe Wind(90.0, 1.5.metersPerSecond)
        native.withForecastWind(report.copy(units = units.copy(speed = "km/h"))) shouldBe native
        native.withForecastWind(report.copy(hourly = report.hourly!!.copy(time = emptyList()))) shouldBe native
        native.withForecastWind(report.copy(units = units.copy(direction = "rad")))
            .hourlyForecast?.single()?.wind shouldBe Wind(speed = 1.5.metersPerSecond)
        val failed = native.copy(failedFeatures = mapOf(SourceFeature.FORECAST to IOException()))
        failed.withForecastWind(report) shouldBe failed
    }
}
