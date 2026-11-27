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

import android.content.Context
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
    fun forecastTabsUseLocalForecastDaysAndKeepConfiguredOrder() {
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
        val tabs = listOf(
            DailyTrendDisplay.TAG_AIR_QUALITY,
            DailyTrendDisplay.TAG_WIND,
            DailyTrendDisplay.TAG_PRECIPITATION,
            DailyTrendDisplay.TAG_FEELS_LIKE,
            DailyTrendDisplay.TAG_TEMPERATURE
        )
        DailyTrendDisplay.availableFor(manila, tabs, justAfterLocalMidnight) shouldBe
            listOf(
                DailyTrendDisplay.TAG_PRECIPITATION,
                DailyTrendDisplay.TAG_FEELS_LIKE,
                DailyTrendDisplay.TAG_TEMPERATURE
            )

        val withWindAndAq = forecast.copy(
            day = forecast.day!!.copy(wind = Wind(speed = 0.metersPerSecond)),
            airQuality = earlier.airQuality
        )
        val boracay = manila.copy(weather = weather.copy(dailyForecast = listOf(earlier, withWindAndAq)))
        DailyTrendDisplay.availableFor(boracay, tabs, justAfterLocalMidnight) shouldBe tabs
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
        val tabs = listOf(DailyTrendDisplay.TAG_WIND, DailyTrendDisplay.TAG_AIR_QUALITY)
        DailyTrendDisplay.availableFor(cached, tabs, justAfterLocalMidnight) shouldBe listOf(DailyTrendDisplay.TAG_WIND)
        DailyTrendDisplay.availableFor(cached.copy(weather = Weather()), tabs, justAfterLocalMidnight) shouldBe
            emptyList()
    }

    @Test
    fun toDailyTrendDisplayList() = runTest {
        val value = "temperature&air_quality&wind&uv_index&precipitation&sunshine&feels_like"
        val list = DailyTrendDisplay.toDailyTrendDisplayList(value)
        list[0] shouldBe DailyTrendDisplay.TAG_TEMPERATURE
        list[1] shouldBe DailyTrendDisplay.TAG_AIR_QUALITY
        list[2] shouldBe DailyTrendDisplay.TAG_WIND
        list[3] shouldBe DailyTrendDisplay.TAG_UV_INDEX
        list[4] shouldBe DailyTrendDisplay.TAG_PRECIPITATION
        list[5] shouldBe DailyTrendDisplay.TAG_SUNSHINE
        list[6] shouldBe DailyTrendDisplay.TAG_FEELS_LIKE
    }

    @Test
    fun toValue() = runTest {
        val list = arrayListOf(
            DailyTrendDisplay.TAG_TEMPERATURE,
            DailyTrendDisplay.TAG_AIR_QUALITY,
            DailyTrendDisplay.TAG_WIND,
            DailyTrendDisplay.TAG_UV_INDEX,
            DailyTrendDisplay.TAG_PRECIPITATION,
            DailyTrendDisplay.TAG_SUNSHINE,
            DailyTrendDisplay.TAG_FEELS_LIKE
        )
        val value = "temperature&air_quality&wind&uv_index&precipitation&sunshine&feels_like"
        DailyTrendDisplay.toValue(list) shouldBe value
    }

    @Test
    fun getSummary() = runTest {
        val context = mockk<Context>()
        every { context.getString(any()) } returns "Name"
        every { context.getString(org.breezyweather.unit.R.string.locale_separator) } returns ", "
        val list = arrayListOf(
            DailyTrendDisplay.TAG_TEMPERATURE,
            DailyTrendDisplay.TAG_AIR_QUALITY,
            DailyTrendDisplay.TAG_WIND,
            DailyTrendDisplay.TAG_UV_INDEX,
            DailyTrendDisplay.TAG_PRECIPITATION,
            DailyTrendDisplay.TAG_SUNSHINE,
            DailyTrendDisplay.TAG_FEELS_LIKE
        )
        val value = "Name, Name, Name, Name, Name, Name, Name"
        DailyTrendDisplay.getSummary(context, list) shouldBe value
    }
}
