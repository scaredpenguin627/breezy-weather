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
import breezyweather.domain.weather.model.Wind
import breezyweather.domain.weather.reference.WeatherCode
import breezyweather.domain.weather.wrappers.CurrentWrapper
import breezyweather.domain.weather.wrappers.DailyWrapper
import breezyweather.domain.weather.wrappers.HalfDayWrapper
import breezyweather.domain.weather.wrappers.HourlyWrapper
import breezyweather.domain.weather.wrappers.TemperatureWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.sources.halfDayPeakWind
import org.breezyweather.unit.precipitation.Precipitation.Companion.millimeters
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import org.breezyweather.unit.temperature.Temperature
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours

private val clockFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val manila = ZoneId.of("Asia/Manila")

internal fun Instant.mapLocalTime(): String = clockFormat.format(atZone(manila)).replace(' ', 'T')

internal fun PanahonMapValue.airTemperature(
    latitude: Double,
    longitude: Double,
    initialization: Instant,
): TemperatureWrapper? {
    val lon = coordinates?.getOrNull(0) ?: return null
    val lat = coordinates.getOrNull(1) ?: return null
    if (!lat.isFinite() || !lon.isFinite() || abs(lon - longitude) > 0.0001 || abs(lat - latitude) > 0.0001) return null
    if (run != clockFormat.format(initialization.atOffset(ZoneOffset.UTC))) return null
    val temp = values?.firstOrNull()?.doubleOrNull?.takeIf(Double::isFinite)?.celsius?.toValidOrNull()
    return temp?.let { TemperatureWrapper(temperature = it) }
}

private fun <T : Any> List<PanahonPoint?>?.readSeries(
    range: LongRange,
    run: Long,
    parse: (JsonPrimitive) -> T?,
): Map<Long, T> =
    buildMap {
        this@readSeries.orEmpty().forEach { point ->
            val clock = point?.time ?: return@forEach
            val value = point.value?.let(parse) ?: return@forEach
            val time = try {
                LocalDateTime.parse(clock, clockFormat).atZone(manila).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                return@forEach
            }
            if (time !in range) return@forEach
            val sinceRun = time - run
            val interval = if (sinceRun <= 90.hours.inWholeMilliseconds) 1.hours else 3.hours
            if (sinceRun % interval.inWholeMilliseconds == 0L) put(time, value)
        }
    }

private fun midnight(now: Long): Long =
    Instant.ofEpochMilli(now).atZone(manila).toLocalDate().atStartOfDay(manila).toInstant().toEpochMilli()

internal fun PanahonPointReport.toPointHours(initialization: Instant, now: Long): List<HourlyWrapper> {
    if (!success || model != "ecmwf" || data == null) throw InvalidOrIncompleteDataException()
    val today = midnight(now)
    val end = today + 120.hours.inWholeMilliseconds
    // The last nighttime period ends at 06:00 on the next date.
    val startRun = initialization.toEpochMilli()
    val range =
        maxOf(today, startRun)..minOf(end + 6.hours.inWholeMilliseconds, startRun + 144.hours.inWholeMilliseconds)
    val conditions = data.icons.readSeries(range, startRun) { iconCode(it.content) }
    val heat = data.heatIndex.readSeries(range, startRun) {
        it.doubleOrNull?.takeIf(Double::isFinite)?.celsius?.toValidOrNull()
    }
    val rain = data.rain.takeIf { rainUnit == "mm" }.readSeries(range, startRun) {
        it.doubleOrNull?.takeIf { n -> n.isFinite() && n >= 0.0 }?.millimeters?.toValidHourlyOrNull()
    } - startRun
    val bearings = data.directions.readSeries(range, startRun) {
        it.doubleOrNull?.takeIf { n -> n in -180.0..360.0 }?.let { n -> (n + 360.0) % 360.0 }
    }
    val speeds = data.speeds.readSeries(range, startRun) {
        it.doubleOrNull?.takeIf { n -> n.isFinite() && n >= 0.0 }?.metersPerSecond?.toValidOrNull()
    }
    return (conditions.keys + heat.keys + rain.keys + speeds.keys).sorted().map { time ->
        HourlyWrapper(
            date = Date(time),
            weatherCode = conditions[time],
            temperature = heat[time]?.let { TemperatureWrapper(feelsLike = it) },
            precipitation = rain[time]?.let { Precipitation(total = it) },
            wind = speeds[time]?.let { Wind(degree = bearings[time], speed = it) }
        )
    }
}

internal fun List<HourlyWrapper>.toWeather(
    temperatures: Map<Long, Temperature>,
    features: List<SourceFeature>,
    now: Long,
): WeatherWrapper {
    val today = midnight(now)
    val end = today + 120.hours.inWholeMilliseconds
    val hours = map { row ->
        val actual = temperatures[row.date.time]
        if (actual != null) row.copy(temperature = TemperatureWrapper(actual, row.temperature?.feelsLike)) else row
    }
    val hourNow = now - now % 1.hours.inWholeMilliseconds
    val failures = mutableMapOf<SourceFeature, Throwable>()
    val current = if (SourceFeature.CURRENT in features) {
        val row = hours.firstOrNull { it.date.time == hourNow }
            ?: temperatures[hourNow]?.let { HourlyWrapper(Date(hourNow), temperature = TemperatureWrapper(it)) }
        if (row == null || (row.weatherCode == null && row.temperature == null && row.wind == null)) {
            failures[SourceFeature.CURRENT] = OutdatedServerDataException()
            null
        } else {
            CurrentWrapper(weatherCode = row.weatherCode, temperature = row.temperature, wind = row.wind)
        }
    } else {
        null
    }
    if (SourceFeature.FORECAST !in features) return WeatherWrapper(current = current, failedFeatures = failures)
    val forecast = hours.filter { it.date.time < end }
    if (forecast.none { it.date.time >= hourNow }) {
        failures[SourceFeature.FORECAST] = OutdatedServerDataException()
        return WeatherWrapper(current = current, failedFeatures = failures)
    }
    val days = (0 until 5).mapNotNull { day ->
        val midnight = today + (day * 24).hours.inWholeMilliseconds
        if (forecast.none {
                it.date.time in midnight until midnight + 24.hours.inWholeMilliseconds
            }
        ) {
            return@mapNotNull null
        }
        DailyWrapper(
            date = Date(midnight),
            day = hours.summarizeHalfDay(midnight + 6.hours.inWholeMilliseconds, daytime = true),
            night = hours.summarizeHalfDay(midnight + 18.hours.inWholeMilliseconds, daytime = false)
        )
    }
    return WeatherWrapper(
        current = current,
        hourlyForecast = forecast,
        dailyForecast = days,
        forecastWindSamples = forecast.mapNotNull { row -> row.wind?.let { row.date to it } }.toMap(),
        failedFeatures = failures
    )
}

private fun List<HourlyWrapper>.summarizeHalfDay(start: Long, daytime: Boolean): HalfDayWrapper? {
    val rows = filter { it.date.time in start until start + 12.hours.inWholeMilliseconds }
    if (rows.isEmpty()) return null
    val air = rows.mapNotNull { it.temperature?.temperature }
    val airExtreme = if (daytime) air.maxOrNull() else air.minOrNull()
    val heat = rows.mapNotNull { it.temperature?.feelsLike }
    val heatExtreme = if (daytime) heat.maxOrNull() else heat.minOrNull()
    // ECMWF precipitation amounts are stamped at the end of their accumulation interval.
    val hourlyRain = this.mapNotNull { h -> h.precipitation?.total?.let { h.date.time to it } }.toMap()
    val completeRain = (1..12).mapNotNull { hourlyRain[start + it.hours.inWholeMilliseconds] }
        .takeIf { it.size == 12 }?.sumOf { it.inMillimeters }?.millimeters
    val representative = rows.mapNotNull { it.weatherCode }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return HalfDayWrapper(
        weatherCode = representative,
        temperature = if (airExtreme != null ||
            heatExtreme != null
        ) {
            TemperatureWrapper(airExtreme, heatExtreme)
        } else {
            null
        },
        precipitation = completeRain?.let { Precipitation(total = it) },
        wind = halfDayPeakWind(rows.mapNotNull { it.wind })
    )
}

private fun iconCode(value: String): WeatherCode? {
    val icon = value.removePrefix("icon-")
    return when {
        "thunderstorm" in icon -> WeatherCode.THUNDERSTORM
        "rain" in icon || "drizzle" in icon -> WeatherCode.RAIN
        "partly-cloudy" in icon -> WeatherCode.PARTLY_CLOUDY
        "cloudy" in icon || "overcast" in icon -> WeatherCode.CLOUDY
        "clear" in icon || "sunny" in icon -> WeatherCode.CLEAR
        "fog" in icon -> WeatherCode.FOG
        "haze" in icon -> WeatherCode.HAZE
        else -> null
    }
}
