package org.breezyweather.sources

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.model.Base
import breezyweather.domain.weather.model.Current
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.HalfDay
import breezyweather.domain.weather.model.Hourly
import breezyweather.domain.weather.model.Weather
import breezyweather.domain.weather.model.Wind
import breezyweather.domain.weather.wrappers.DailyWrapper
import breezyweather.domain.weather.wrappers.HourlyWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import org.junit.jupiter.api.Test
import java.util.Date

/**
 * TODO: To be completed
 */
class CommonConverterTest {

    @Test
    fun movingAPanahonLocationInvalidatesOnlyItsCoordinateDependentWind() {
        val time = Date(3_600_000)
        val wind = Wind(degree = 90.0, speed = 4.metersPerSecond)
        val day = HalfDay(weatherText = "Clear", wind = wind)
        val prior = Weather(
            base = Base(forecastUpdateTime = time, currentUpdateTime = time),
            current = Current(weatherText = "Clear", wind = wind),
            dailyForecast = listOf(Daily(time, day = day, night = day)),
            hourlyForecast = listOf(Hourly(time, weatherText = "Clear", wind = wind)),
            forecastWindSamples = mapOf(time to wind)
        )
        val location =
            Location(weather = prior, forecastSourcePreference = "panahon", currentSourcePreference = "panahon")
        val moved = location.withoutMovedPanahonWind(true).weather!!
        moved.forecastWindSamples.isEmpty() shouldBe true
        moved.base shouldBe prior.base.copy(forecastUpdateTime = null)
        moved.dailyForecast.single().day shouldBe day.copy(wind = null)
        moved.dailyForecast.single().night shouldBe day.copy(wind = null)
        moved.hourlyForecast.single() shouldBe prior.hourlyForecast.single().copy(wind = null)
        moved.current shouldBe prior.current!!.copy(wind = null)
        location.withoutMovedPanahonWind(false).weather shouldBe prior
        location.copy(currentSourcePreference = "pagasa").withoutMovedPanahonWind(true).weather?.current shouldBe
            prior.current
        location.copy(forecastSourcePreference = "pagasa").withoutMovedPanahonWind(true).weather shouldBe prior
    }

    @Test
    fun freshPanahonForecastClearsOnlyWindFromPrependedHistory() {
        val previousWind = Wind(speed = 4.metersPerSecond)
        val newWind = Wind(speed = 0.metersPerSecond)
        val history = Weather(
            dailyForecast = listOf(
                Daily(Date(0), day = HalfDay(wind = previousWind), night = HalfDay(wind = previousWind))
            ),
            hourlyForecast = listOf(Hourly(Date(0), weatherText = "Cloudy", wind = previousWind))
        )
        val newForecast = WeatherWrapper(
            dailyForecast = listOf(DailyWrapper(Date(86_400_000))),
            hourlyForecast = listOf(HourlyWrapper(Date(3_600_000)), HourlyWrapper(Date(7_200_000), wind = newWind))
        )
        val merged = completeNewWeatherWithPreviousData(newForecast, history, Date(0), null, null, false)
        merged.hourlyForecast!!.map { it.wind } shouldBe listOf(null, null, newWind)
        merged.hourlyForecast!!.first().weatherText shouldBe "Cloudy"
        merged.dailyForecast!!.first().day?.wind shouldBe null
        merged.dailyForecast!!.first().night?.wind shouldBe null
        val cached = completeNewWeatherWithPreviousData(newForecast, history, Date(0), null, null, true)
        cached.hourlyForecast!!.first().wind shouldBe previousWind
        cached.dailyForecast!!.first().night?.wind shouldBe previousWind
    }

    @Test
    fun currentWindUsesOnlyAnAvailablePanahonTimeInTheCurrentHour() {
        val wind = Wind(speed = 3.metersPerSecond)
        val first = Hourly(Date(0), wind = wind)
        val future = Hourly(Date(10_800_000), weatherText = "Clear", wind = wind)
        val rows = listOf(first, future)
        rows.hourForCurrentConditions(3_599_999, "panahon")?.wind shouldBe wind
        rows.hourForCurrentConditions(3_600_000, "panahon")?.wind shouldBe null
        rows.hourForCurrentConditions(7_200_000, "panahon") shouldBe future.copy(wind = null)
        rows.hourForCurrentConditions(7_200_000, "pagasa") shouldBe future
        rows.hourForCurrentConditions(10_800_000, "panahon")?.wind shouldBe wind

        val cached = Current(wind = wind, weatherText = "Clear")
        val location = Location(forecastSourcePreference = "panahon", currentSourcePreference = "panahon")
        cached.toCachedCurrentWrapper(location) shouldBe cached.toCurrentWrapper().copy(wind = null)
        cached.toCachedCurrentWrapper(location.copy(currentSourcePreference = null)).wind shouldBe null
        cached.toCachedCurrentWrapper(location.copy(currentSourcePreference = "pagasa")).wind shouldBe wind
        cached.toCachedCurrentWrapper(location.copy(forecastSourcePreference = "openmeteo")).wind shouldBe wind
    }

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
