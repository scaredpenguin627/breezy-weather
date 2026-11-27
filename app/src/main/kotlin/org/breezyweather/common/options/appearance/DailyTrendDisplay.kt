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

package org.breezyweather.common.options.appearance

import androidx.annotation.StringRes
import breezyweather.domain.location.model.Location
import org.breezyweather.R
import org.breezyweather.common.extensions.toTimezoneSpecificHour
import org.breezyweather.domain.weather.model.getIndex
import java.util.Date

enum class DailyTrendDisplay(val id: String, @StringRes val nameId: Int) {
    TAG_TEMPERATURE("temperature", R.string.conditions),
    TAG_AIR_QUALITY("air_quality", R.string.air_quality),
    TAG_WIND("wind", R.string.wind),
    TAG_PRECIPITATION("precipitation", R.string.precipitation),
    TAG_FEELS_LIKE("feels_like", R.string.temperature_feels_like),
    ;

    fun hasData(location: Location, now: Date = Date()): Boolean {
        val days = location.weather?.dailyForecast.orEmpty()
        val today = now.toTimezoneSpecificHour(location.timeZone)
        // Only use history if the entire forecast is in the past (for example, while offline).
        val forecast = days.dropWhile { it.date < today }.ifEmpty { days }
        return forecast.any { day ->
            when (this) {
                TAG_TEMPERATURE -> listOfNotNull(day.day, day.night).any {
                    it.temperature?.temperature != null || it.weatherCode != null || !it.weatherText.isNullOrBlank()
                }
                TAG_AIR_QUALITY -> day.airQuality?.getIndex() != null
                TAG_WIND -> day.day?.wind?.speed != null || day.night?.wind?.speed != null
                TAG_PRECIPITATION -> day.day?.precipitation?.total != null || day.night?.precipitation?.total != null
                TAG_FEELS_LIKE ->
                    day.day?.temperature?.feelsLikeTemperature != null ||
                        day.night?.temperature?.feelsLikeTemperature != null
            }
        }
    }
}
