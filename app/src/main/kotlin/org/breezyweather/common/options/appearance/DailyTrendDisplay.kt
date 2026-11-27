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

import android.content.Context
import androidx.annotation.StringRes
import breezyweather.domain.location.model.Location
import org.breezyweather.R
import org.breezyweather.common.extensions.toTimezoneSpecificHour
import org.breezyweather.common.options.BaseEnum
import org.breezyweather.domain.weather.model.getIndex
import java.util.Date

enum class DailyTrendDisplay(
    override val id: String,
    @StringRes val nameId: Int,
) : BaseEnum {

    TAG_TEMPERATURE("temperature", R.string.conditions),
    TAG_AIR_QUALITY("air_quality", R.string.air_quality),
    TAG_WIND("wind", R.string.wind),
    TAG_UV_INDEX("uv_index", R.string.uv_index),
    TAG_PRECIPITATION("precipitation", R.string.precipitation),
    TAG_SUNSHINE("sunshine", R.string.sunshine),
    TAG_FEELS_LIKE("feels_like", R.string.temperature_feels_like),
    ;

    companion object {

        fun toDailyTrendDisplayList(
            value: String?,
        ) = if (value.isNullOrEmpty()) {
            entries.toMutableList()
        } else {
            try {
                value.split("&").toTypedArray().mapNotNull { cardId ->
                    entries.firstOrNull { it.id == cardId }
                }
            } catch (e: Exception) {
                entries.toMutableList()
            }
        }

        internal fun availableFor(
            location: Location,
            configured: List<DailyTrendDisplay>,
            now: Date = Date(),
        ): List<DailyTrendDisplay> {
            val days = location.weather?.dailyForecast.orEmpty()
            if (days.isEmpty()) return emptyList()

            // The chart initially scrolls to today. A cached reading from yesterday alone
            // must not offer an empty forecast tab. Keep history accessible if the whole forecast is old.
            val today = now.toTimezoneSpecificHour(location.timeZone)
            val forecast = days.dropWhile { it.date < today }.ifEmpty { days }
            return configured.filter { trend ->
                when (trend) {
                    TAG_TEMPERATURE -> true
                    TAG_AIR_QUALITY -> forecast.any { it.airQuality?.getIndex() != null }
                    TAG_WIND -> forecast.any { it.day?.wind?.speed != null || it.night?.wind?.speed != null }
                    TAG_UV_INDEX -> forecast.any { it.uV?.index != null }
                    TAG_PRECIPITATION -> forecast.any {
                        it.day?.precipitation?.total != null || it.night?.precipitation?.total != null
                    }
                    TAG_SUNSHINE -> forecast.any { (it.sunshineDuration?.inWholeMinutes ?: 0) > 0 }
                    TAG_FEELS_LIKE -> forecast.any {
                        it.day?.temperature?.feelsLikeTemperature != null ||
                            it.night?.temperature?.feelsLikeTemperature != null
                    }
                }
            }
        }

        fun toValue(list: List<DailyTrendDisplay>): String {
            return list.joinToString("&") { item ->
                item.id
            }
        }

        fun getSummary(context: Context, list: List<DailyTrendDisplay>): String {
            return list.joinToString(context.getString(org.breezyweather.unit.R.string.locale_separator)) { item ->
                item.getName(context)
            }
        }
    }

    override val valueArrayId = 0
    override val nameArrayId = 0

    override fun getName(context: Context) = context.getString(nameId)
}
