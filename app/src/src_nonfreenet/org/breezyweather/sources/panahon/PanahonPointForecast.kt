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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class PanahonPointReport(
    val success: Boolean,
    val model: String? = null,
    @SerialName("rain_unit") val rainUnit: String? = null,
    val data: PanahonPointFields? = null,
)

@Serializable
internal data class PanahonPointFields(
    @SerialName("icon") val icons: List<PanahonPoint?>? = null,
    @SerialName("accumulated_rain_1h") val rain: List<PanahonPoint?>? = null,
    @SerialName("heat_index") val heatIndex: List<PanahonPoint?>? = null,
    @SerialName("wind_speed") val speeds: List<PanahonPoint?>? = null,
    @SerialName("wind_direction") val directions: List<PanahonPoint?>? = null,
)

@Serializable
internal data class PanahonPoint(
    @SerialName("forecast_at") val time: String? = null,
    val value: JsonPrimitive? = null,
)

@Serializable
internal data class PanahonMapValue(
    val coordinates: List<Double?>? = null,
    val values: List<JsonPrimitive?>? = null,
    val run: String? = null,
)
