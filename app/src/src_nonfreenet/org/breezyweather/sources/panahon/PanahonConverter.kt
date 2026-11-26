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
import breezyweather.domain.weather.model.Precipitation
import breezyweather.domain.weather.reference.WeatherCode
import breezyweather.domain.weather.wrappers.CurrentWrapper
import breezyweather.domain.weather.wrappers.DailyWrapper
import breezyweather.domain.weather.wrappers.HalfDayWrapper
import breezyweather.domain.weather.wrappers.HourlyWrapper
import breezyweather.domain.weather.wrappers.TemperatureWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.unit.precipitation.Precipitation.Companion.millimeters
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

internal fun PanahonResponse.toWeather(features: List<SourceFeature>, now: Long): WeatherWrapper {
    if (!success || data == null) throw InvalidOrIncompleteDataException()
    val days = data.dc.orEmpty()
    val firstDate = days.firstOrNull()?.dt ?: throw InvalidOrIncompleteDataException()
    val dates = forecastDates(now, days.size)
    val midnight = dates[firstDate] ?: throw OutdatedServerDataException()
    val failures = mutableMapOf<SourceFeature, Throwable>()

    val current = if (SourceFeature.CURRENT in features) {
        val row = data.nc
        val offset = clockOffset(row?.time)
        when {
            row == null || offset == null || !row.hasWeather() -> {
                failures[SourceFeature.CURRENT] = InvalidOrIncompleteDataException()
                null
            }
            now - (midnight + offset) !in -1.hours.inWholeMilliseconds..2.hours.inWholeMilliseconds -> {
                failures[SourceFeature.CURRENT] = OutdatedServerDataException()
                null
            }
            else -> CurrentWrapper(
                weatherText = row.desc,
                weatherCode = weatherCode(row.desc),
                temperature = temperature(row.tmp, row.hindex)
            )
        }
    } else {
        null
    }

    if (SourceFeature.FORECAST !in features) return WeatherWrapper(current = current, failedFeatures = failures)

    val daily = days.mapNotNull { row ->
        val date = dates[row.dt] ?: return@mapNotNull null
        val high = temperature(row.tmax, row.hmax)
        val low = temperature(row.tmin, row.hmin)
        if (high == null && low == null && row.desc.isNullOrBlank()) return@mapNotNull null
        DailyWrapper(
            date = Date(date),
            day = HalfDayWrapper(weatherText = row.desc, weatherCode = weatherCode(row.desc), temperature = high),
            night = low?.let { HalfDayWrapper(temperature = it) }
        )
    }
    // The 24 hourly rows only carry a Manila clock time. Earlier clocks belong to the next day.
    val start = clockOffset(data.nc?.time) ?: clockOffset(data.hc?.firstOrNull()?.time)
    val hourly = data.hc.orEmpty().mapNotNull { row ->
        val offset = clockOffset(row.time) ?: return@mapNotNull null
        val date = midnight + offset + if (start != null && offset < start) 1.days.inWholeMilliseconds else 0L
        val rain = quantity(row.tprecip, "mm")?.takeIf { it >= 0 }
        if (!row.hasWeather() && rain == null) return@mapNotNull null
        HourlyWrapper(
            date = Date(date),
            weatherText = row.desc,
            weatherCode = weatherCode(row.desc),
            temperature = temperature(row.tmp, row.hindex),
            precipitation = rain?.let { Precipitation(total = it.millimeters) }
        )
    }.distinctBy { it.date }.sortedBy { it.date }

    if (daily.isEmpty() || hourly.isEmpty()) failures[SourceFeature.FORECAST] = InvalidOrIncompleteDataException()
    return WeatherWrapper(current = current, dailyForecast = daily, hourlyForecast = hourly, failedFeatures = failures)
}

// The dashboard silently returns Manila when it does not recognize the coordinate parameters.
// Its metadata echoes the requested point rounded to four decimal places.
internal fun PanahonCoordinates?.matches(latitude: Double, longitude: Double): Boolean {
    val echoedLat = this?.lat?.toDoubleOrNull() ?: return false
    val echoedLon = this.lon?.toDoubleOrNull() ?: return false
    return abs(latitude - echoedLat) <= 0.0001 && abs(longitude - echoedLon) <= 0.0001
}

// Dates are MM/dd without a year. Include yesterday for a cached response around local midnight.
private fun forecastDates(now: Long, count: Int): Map<String, Long> {
    val zone = TimeZone.getTimeZone("Asia/Manila")
    val format = SimpleDateFormat("MM/dd", Locale.US).apply { timeZone = zone }
    val day = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -1)
    }
    return buildMap {
        repeat(count + 2) {
            put(format.format(day.time), day.timeInMillis)
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
    }
}

private val clock = Regex("""(1[0-2]|[1-9]):([0-5]\d) (AM|PM)""")

private fun clockOffset(value: String?): Long? {
    val (hour, minute, period) = clock.matchEntire(value.orEmpty())?.destructured ?: return null
    val hour24 = hour.toInt() % 12 + if (period == "PM") 12 else 0
    return (hour24 * 60 + minute.toInt()) * 60_000L
}

private fun PanahonHour.hasWeather() =
    !desc.isNullOrBlank() || temperature(tmp, hindex) != null

private fun temperature(value: String?, heatIndex: String?): TemperatureWrapper? {
    val temp = quantity(value, "°C")?.celsius
    val feelsLike = quantity(heatIndex, "°C")?.celsius
    return if (temp == null && feelsLike == null) null else TemperatureWrapper(temp, feelsLike)
}

private fun quantity(value: String?, unit: String): Double? =
    value?.trim()?.takeIf { it.endsWith(unit) }?.removeSuffix(unit)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun weatherCode(description: String?): WeatherCode? {
    val text = description?.lowercase(Locale.ROOT) ?: return null
    return when {
        "thunderstorm" in text -> WeatherCode.THUNDERSTORM
        "rain" in text || "drizzle" in text -> WeatherCode.RAIN
        "partly cloudy" in text -> WeatherCode.PARTLY_CLOUDY
        "cloudy" in text || "overcast" in text -> WeatherCode.CLOUDY
        "clear" in text || "sunny" in text -> WeatherCode.CLEAR
        "fog" in text -> WeatherCode.FOG
        "haze" in text -> WeatherCode.HAZE
        else -> null
    }
}
