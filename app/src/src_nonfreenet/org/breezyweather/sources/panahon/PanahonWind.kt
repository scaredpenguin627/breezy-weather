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
import org.breezyweather.sources.halfDayPeakWind
import org.breezyweather.unit.speed.Speed.Companion.metersPerSecond
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@Serializable
internal data class OpenMeteoWindReport(
    val hourly: OpenMeteoWindHourly? = null,
    @SerialName("hourly_units") val units: OpenMeteoWindUnits? = null,
)

@Serializable
internal data class OpenMeteoWindUnits(
    val time: String? = null,
    @SerialName("wind_speed_10m") val speed: String? = null,
    @SerialName("wind_direction_10m") val direction: String? = null,
)

@Serializable
internal data class OpenMeteoWindHourly(
    val time: List<Long>,
    @SerialName("wind_speed_10m") val speed: List<Double?>,
    @SerialName("wind_direction_10m") val direction: List<Double?>? = null,
)

internal fun WeatherWrapper.withForecastWind(report: OpenMeteoWindReport): WeatherWrapper {
    if (SourceFeature.FORECAST in failedFeatures.orEmpty()) return this
    val data = report.hourly ?: return this
    if (report.units?.time != "unixtime" || report.units.speed != "m/s" || data.time.size != data.speed.size) {
        return this
    }
    val directions = data.direction?.takeIf { report.units.direction == "°" && it.size == data.time.size }
    val winds = buildMap {
        data.time.forEachIndexed { i, epochSeconds ->
            val speed = data.speed[i]?.takeIf { it.isFinite() && it >= 0 }?.metersPerSecond?.toValidOrNull()
            if (speed != null) {
                put(
                    epochSeconds.seconds.inWholeMilliseconds,
                    Wind(
                        speed = speed,
                        degree = directions?.get(i)?.takeIf { it in 0.0..360.0 }
                    )
                )
            }
        }
        hourlyForecast.orEmpty().forEach { hour ->
            val native = hour.wind ?: return@forEach
            val wind = native.withMissingSpeedFrom(get(hour.date.time))
            if (wind?.speed != null) put(hour.date.time, wind)
        }
    }
    if (winds.isEmpty()) return this

    return copy(
        hourlyForecast = hourlyForecast?.map { hour ->
            val wind = winds[hour.date.time]
            if (wind == null || wind == hour.wind) hour else hour.copy(wind = wind)
        },
        dailyForecast = dailyForecast?.map { day ->
            val midnight = day.date.time
            // Breezy uses 06:00–17:59 and 18:00–05:59 local time, keeping the direction at peak speed.
            day.copy(
                day = day.day.addWind(winds, midnight, 6 until 18),
                night = day.night.addWind(winds, midnight, 18 until 30)
            )
        }
    )
}

private fun HalfDayWrapper?.addWind(winds: Map<Long, Wind>, midnight: Long, hours: IntRange): HalfDayWrapper? {
    if (this?.wind?.speed != null) return this
    val peak = halfDayPeakWind(hours.mapNotNull { winds[midnight + it.hours.inWholeMilliseconds] })
        ?: return this
    return (this ?: HalfDayWrapper()).copy(wind = this?.wind.withMissingSpeedFrom(peak))
}

private fun Wind?.withMissingSpeedFrom(supplement: Wind?): Wind? = when {
    this?.speed != null || supplement == null -> this
    this == null -> supplement
    else -> copy(speed = supplement.speed, degree = degree ?: supplement.degree)
}
