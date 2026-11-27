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
import breezyweather.domain.weather.model.Wind
import breezyweather.domain.weather.wrappers.HalfDayWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.breezyweather.sources.halfDayPeakWind
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.hours

@Serializable
internal data class PanahonWindReport(
    val success: Boolean,
    val model: String? = null,
    val data: PanahonWindData? = null,
)

@Serializable
internal data class PanahonWindData(
    @SerialName("wind_speed") val speeds: List<PanahonWindPoint?>? = null,
    @SerialName("wind_direction") val directions: List<PanahonWindPoint?>? = null,
)

@Serializable
internal data class PanahonWindPoint(
    @SerialName("forecast_at") val time: String? = null,
    val value: JsonPrimitive? = null,
)

private val mapForecastTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
private val mapTimeZone = ZoneId.of("Asia/Manila")

private fun PanahonWindPoint.timestamp(): Long? {
    val clock = time ?: return null
    return try {
        LocalDateTime.parse(clock, mapForecastTime).atZone(mapTimeZone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun WeatherWrapper.withForecastWind(report: PanahonWindReport, initialization: Instant): WeatherWrapper {
    val data = report.data ?: return this
    if (!report.success || report.model != "ecmwf" || SourceFeature.FORECAST in failedFeatures.orEmpty()) return this

    val start = initialization.toEpochMilli()
    val visibleTimes = start..(start + 144.hours.inWholeMilliseconds)
    val bearings = buildMap {
        data.directions.orEmpty().forEach { point ->
            val bearing = point?.value?.doubleOrNull?.takeIf { it in -180.0..360.0 } ?: return@forEach
            point.timestamp()?.let { put(it, (bearing + 360.0) % 360.0) }
        }
    }
    val officialWinds = buildMap {
        data.speeds.orEmpty().forEach { point ->
            val speed = point?.value?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }
                ?.metersPerSecond?.toValidOrNull() ?: return@forEach
            val time = point.timestamp() ?: return@forEach
            if (time in visibleTimes) put(time, Wind(speed = speed, degree = bearings[time]))
        }
    }
    if (officialWinds.isEmpty()) return this
    val winds = officialWinds.toMutableMap().apply {
        hourlyForecast.orEmpty().forEach { hour ->
            val native = hour.wind ?: return@forEach
            val wind = native.withMissingSpeedFrom(get(hour.date.time))
            if (wind?.speed != null) put(hour.date.time, wind)
        }
    }
    val days = dailyForecast.orEmpty().map { it.date.time until it.date.time + 24.hours.inWholeMilliseconds }
    return copy(
        forecastWindSamples = officialWinds.filterKeys { time -> days.any { time in it } }.mapKeys { Date(it.key) },
        hourlyForecast = hourlyForecast?.map { hour ->
            val wind = winds[hour.date.time]
            if (wind == null || wind == hour.wind) hour else hour.copy(wind = wind)
        },
        dailyForecast = dailyForecast?.map { day ->
            val midnight = day.date.time
            val morning = midnight + 6.hours.inWholeMilliseconds
            val evening = midnight + 18.hours.inWholeMilliseconds
            // Manila day 06:00–17:59; night 18:00–05:59, including all available samples.
            day.copy(
                day = day.day.addWind(winds, morning, evening),
                night = day.night.addWind(winds, evening, morning + 24.hours.inWholeMilliseconds)
            )
        }
    )
}

private fun HalfDayWrapper?.addWind(winds: Map<Long, Wind>, start: Long, end: Long): HalfDayWrapper? {
    if (this?.wind?.speed != null) return this
    val peak = halfDayPeakWind(winds.filterKeys { it >= start && it < end }.values.toList()) ?: return this
    return (this ?: HalfDayWrapper()).copy(wind = this?.wind.withMissingSpeedFrom(peak))
}

private fun Wind?.withMissingSpeedFrom(supplement: Wind?): Wind? = when {
    this?.speed != null || supplement == null -> this
    this == null -> supplement
    else -> copy(speed = supplement.speed, degree = degree ?: supplement.degree)
}
