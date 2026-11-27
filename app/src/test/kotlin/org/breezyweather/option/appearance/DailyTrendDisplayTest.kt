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

package org.breezyweather.option.appearance

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.model.Current
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.HalfDay
import breezyweather.domain.weather.model.Precipitation
import breezyweather.domain.weather.model.Temperature
import breezyweather.domain.weather.model.Weather
import breezyweather.domain.weather.model.Wind
import io.kotest.matchers.shouldBe
import org.breezyweather.common.options.appearance.DailyTrendDisplay
import org.breezyweather.unit.pollutant.PollutantConcentration.Companion.microgramsPerCubicMeter
import org.breezyweather.unit.precipitation.Precipitation.Companion.millimeters
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import java.util.TimeZone

class DailyTrendDisplayTest {
    private val previousDay = Date.from(Instant.parse("2026-11-25T16:00:00Z"))
    private val forecastDay = Date.from(Instant.parse("2026-11-26T16:00:00Z"))
    private val justAfterLocalMidnight = Date.from(Instant.parse("2026-11-26T16:05:00Z"))

    @Test
    fun sameFixedDimensionsForEveryLocation() {
        DailyTrendDisplay.entries.map { it.id } shouldBe
            listOf("temperature", "air_quality", "wind", "precipitation", "feels_like")
    }

    @Test
    fun dataAvailabilityDoesNotSubstituteCurrentOrYesterdayForDailyForecasts() {
        val forecast = Daily(
            date = forecastDay,
            day = HalfDay(temperature = Temperature(29.celsius, sourceFeelsLike = 32.celsius)),
            night = HalfDay(precipitation = Precipitation(total = 0.millimeters))
        )
        val earlier = Daily(
            date = previousDay,
            day = HalfDay(wind = Wind(speed = 3.metersPerSecond)),
            airQuality = AirQuality(pM25 = 14.microgramsPerCubicMeter)
        )
        val weather = Weather(
            current = Current(airQuality = AirQuality(reportedAqi = 26)),
            dailyForecast = listOf(earlier, forecast)
        )
        val manila = Location(timeZone = TimeZone.getTimeZone("Asia/Manila"), weather = weather)
        DailyTrendDisplay.TAG_AIR_QUALITY.hasData(manila, justAfterLocalMidnight) shouldBe false
        DailyTrendDisplay.TAG_WIND.hasData(manila, justAfterLocalMidnight) shouldBe false
        DailyTrendDisplay.TAG_TEMPERATURE.hasData(manila, justAfterLocalMidnight) shouldBe true
        DailyTrendDisplay.TAG_PRECIPITATION.hasData(manila, justAfterLocalMidnight) shouldBe true
        DailyTrendDisplay.TAG_FEELS_LIKE.hasData(manila, justAfterLocalMidnight) shouldBe true

        val withWindAndAq = forecast.copy(
            day = forecast.day!!.copy(wind = Wind(speed = 0.metersPerSecond)),
            airQuality = earlier.airQuality
        )
        val boracay = manila.copy(weather = weather.copy(dailyForecast = listOf(earlier, withWindAndAq)))
        DailyTrendDisplay.entries.all { it.hasData(boracay, justAfterLocalMidnight) } shouldBe true
    }

    @Test
    fun weatherDescriptionAloneSupportsConditions() {
        val location = Location(
            timeZone = TimeZone.getTimeZone("Asia/Manila"),
            weather = Weather(
                dailyForecast = listOf(Daily(date = forecastDay, night = HalfDay(weatherText = "Cloudy skies")))
            )
        )
        DailyTrendDisplay.TAG_TEMPERATURE.hasData(location, justAfterLocalMidnight) shouldBe true
        DailyTrendDisplay.TAG_FEELS_LIKE.hasData(location, justAfterLocalMidnight) shouldBe false
    }

    @Test
    fun pastOnlyForecastKeepsItsAvailableOfflineCharts() {
        val cached = Location(
            timeZone = TimeZone.getTimeZone("Asia/Manila"),
            weather = Weather(
                dailyForecast = listOf(
                    Daily(date = previousDay, night = HalfDay(wind = Wind(speed = 2.metersPerSecond)))
                )
            )
        )
        DailyTrendDisplay.TAG_WIND.hasData(cached, justAfterLocalMidnight) shouldBe true
        DailyTrendDisplay.TAG_AIR_QUALITY.hasData(cached, justAfterLocalMidnight) shouldBe false
        DailyTrendDisplay.TAG_TEMPERATURE.hasData(cached, justAfterLocalMidnight) shouldBe false
        val empty = cached.copy(weather = Weather())
        DailyTrendDisplay.entries.none { it.hasData(empty, justAfterLocalMidnight) } shouldBe true
    }
}
