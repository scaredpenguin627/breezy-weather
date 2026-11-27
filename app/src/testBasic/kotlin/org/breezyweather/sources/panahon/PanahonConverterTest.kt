/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.panahon

import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.reference.WeatherCode
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

class PanahonConverterTest {
    // Local midnight at the beginning of the five-day view.
    private val init = Instant.parse("2026-12-30T16:00:00Z")
    private val features = listOf(SourceFeature.CURRENT, SourceFeature.FORECAST)

    private fun at(h: Int): Instant = init.plusSeconds(h * 3600L)
    private fun point(h: Int, value: JsonPrimitive) = PanahonPoint(at(h).mapLocalTime().replace('T', ' '), value)
    private fun number(h: Int, value: Number) = point(h, JsonPrimitive(value))
    private fun icon(h: Int, value: String) = point(h, JsonPrimitive(value))
    private val report = PanahonPointReport(
        success = true,
        model = "ecmwf",
        rainUnit = "mm",
        data = PanahonPointFields(
            icons = listOf(icon(6, "icon-clear-skies"), icon(102, "icon-light-rains")),
            rain = (7..18).map { number(it, 0.5) } + number(102, 1.0),
            heatIndex = listOf(number(6, 30), number(17, 34), number(102, 31), number(123, 27)),
            speeds = listOf(number(6, 0), number(17, 4), number(102, 2), number(123, 7)),
            directions = listOf(number(17, -90), number(102, 90), number(123, 360))
        )
    )

    private fun PanahonPointReport.toWeather(init: Instant, features: List<SourceFeature>, now: Long) =
        toPointHours(init, now).toWeather(emptyMap(), features, now)

    @Test
    fun joinsActualWebsiteFieldsAcrossDatesAndDoesNotSubstituteHeatIndexForTemperature() {
        val result = report.toWeather(init, features, at(6).plusSeconds(2500).toEpochMilli())
        result.failedFeatures shouldBe emptyMap()
        result.current?.weatherCode shouldBe WeatherCode.CLEAR
        result.current?.temperature?.feelsLike?.inCelsius shouldBe 30.0
        result.current?.temperature?.temperature shouldBe null
        result.current?.wind?.speed?.inMetersPerSecond shouldBe 0.0
        val hours = (6..18) + 102
        val hourly = result.hourlyForecast!!
        hourly.map { it.date } shouldBe hours.map { Date.from(at(it)) }
        hourly.last().weatherCode shouldBe WeatherCode.RAIN
        hourly.last().precipitation?.total?.inMillimeters shouldBe 1.0
        val days = result.dailyForecast!!
        days.map { it.date } shouldBe listOf(Date.from(init), Date.from(at(96)))
        days.first().day?.temperature?.feelsLike?.inCelsius shouldBe 34.0
        days.first().day?.temperature?.temperature shouldBe null
        days.first().day?.precipitation?.total?.inMillimeters shouldBe 6.0
        days.last().day?.precipitation shouldBe null
        days.last().night?.temperature?.feelsLike?.inCelsius shouldBe 27.0
        days.last().night?.wind?.speed?.inMetersPerSecond shouldBe 7.0
        days.last().night?.wind?.degree shouldBe 0.0
        result.forecastWindSamples.keys shouldBe listOf(6, 17, 102).map { Date.from(at(it)) }.toSet()
        result.forecastWindSamples[Date.from(at(17))]?.degree shouldBe 270.0
    }

    @Test
    fun trueTemperaturesAreJoinedByHourSeparatelyFromHeatIndex() {
        val rows = report.toPointHours(init, at(6).toEpochMilli())
        val actual = listOf(6 to 28, 17 to 31, 102 to 29, 123 to 25)
            .associate { (hour, temp) -> at(hour).toEpochMilli() to temp.celsius }
        val result = rows.toWeather(actual, features, at(6).toEpochMilli())
        result.current?.temperature?.temperature?.inCelsius shouldBe 28.0
        result.current?.temperature?.feelsLike?.inCelsius shouldBe 30.0
        result.hourlyForecast!![1].temperature?.temperature shouldBe null
        val days = result.dailyForecast!!
        days.first().day?.temperature?.temperature?.inCelsius shouldBe 31.0
        days.last().night?.temperature?.temperature?.inCelsius shouldBe 25.0
    }

    @Test
    fun doesNotBorrowFutureOrStaleHoursForCurrent() {
        val missing = report.toWeather(init, features, at(5).plusSeconds(3599).toEpochMilli())
        missing.current shouldBe null
        missing.failedFeatures?.keys shouldBe setOf(SourceFeature.CURRENT)
        val stale = report.toWeather(init, features, at(146).toEpochMilli())
        stale.failedFeatures?.values?.all { it is OutdatedServerDataException } shouldBe true
        stale.dailyForecast shouldBe null
        val currentOnly = report.toWeather(init, listOf(SourceFeature.CURRENT), at(6).toEpochMilli())
        currentOnly.hourlyForecast shouldBe null
        currentOnly.dailyForecast shouldBe null
    }

    @Test
    fun decodesPointJsonAndSkipsBadTimestampsAndUnknownPrecipitationUnits() {
        val decoded = Json.decodeFromString<PanahonPointReport>(
            """{"success":true,"model":"ecmwf","rain_unit":"unknown","data":{
            "icon":[{"forecast_at":"2026-12-31 06:00:00","value":"icon-cloudy-skies"}],
            "heat_index":[{"forecast_at":"2026-12-31 06:00:00","value":33.2}],
            "accumulated_rain_1h":[{"forecast_at":"2026-12-31 06:00:00","value":2}],
            "wind_speed":[null,{},{"forecast_at":"2026-02-30 00:00:00","value":4}],
            "wind_direction":[{"forecast_at":"2026-12-31 06:00:00","value":25}]
            }}"""
        )
        val row = decoded.toWeather(init, features, at(6).toEpochMilli()).hourlyForecast!!.single()
        row.weatherCode shouldBe WeatherCode.CLOUDY
        row.precipitation shouldBe null
        row.wind shouldBe null
        row.temperature?.feelsLike?.inCelsius shouldBe 33.2
    }

    @Test
    fun checksTemperatureLayerCoordinatesAndRun() {
        val value = PanahonMapValue(listOf(121.91932, 11.98013), listOf(JsonPrimitive(28.4)), "2026-12-30 16:00:00")
        value.airTemperature(11.98013, 121.91932, init)?.temperature?.inCelsius shouldBe 28.4
        value.airTemperature(14.5995, 120.9842, init) shouldBe null
        value.airTemperature(11.98013, 121.91932, at(6)) shouldBe null
    }

    @Test
    fun dropsInitializationRainAndOffGridForecastTimes() {
        val values = PanahonPointFields(
            rain = listOf(number(0, 0)),
            speeds = listOf(number(91, 3), number(92, 4))
        )
        val hours = report.copy(data = values).toPointHours(init, init.toEpochMilli())
        hours.isEmpty() shouldBe true
        hours.toWeather(emptyMap(), features, init.toEpochMilli()).failedFeatures?.keys shouldBe features.toSet()
    }
}
