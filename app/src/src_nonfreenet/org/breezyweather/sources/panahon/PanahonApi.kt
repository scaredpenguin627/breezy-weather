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

import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

internal interface PanahonApi {
    @GET("api/v1/nowcast/dashboard")
    fun getDashboard(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
    ): Observable<PanahonResponse>

    @GET(
        "https://api.open-meteo.com/v1/forecast?hourly=wind_speed_10m,wind_direction_10m" +
            "&wind_speed_unit=ms&timeformat=unixtime&timezone=Asia%2FManila&forecast_days=6&past_days=1"
    )
    fun getForecastWind(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
    ): Observable<OpenMeteoWindReport>

    @Headers("Accept: application/json")
    @GET("https://www.panahon.gov.ph/api/v1/cap-alerts")
    fun getAlerts(): Observable<PanahonAlertResponse>
}

@Serializable
internal data class PanahonResponse(
    val success: Boolean,
    val meta: PanahonCoordinates? = null,
    val data: PanahonDashboard? = null,
)

@Serializable
internal data class PanahonCoordinates(
    val lat: String? = null,
    val lon: String? = null,
    val municipality: String? = null,
    val province: String? = null,
    val region: String? = null,
)

@Serializable
internal data class PanahonDashboard(
    val nc: PanahonHour? = null,
    val hc: List<PanahonHour>? = null,
    val dc: List<PanahonDay>? = null,
)

@Serializable
internal data class PanahonHour(
    val time: String? = null,
    val desc: String? = null,
    val tmp: String? = null,
    val hindex: String? = null,
    val tprecip: String? = null,
)

@Serializable
internal data class PanahonDay(
    val dt: String? = null,
    val desc: String? = null,
    val tmax: String? = null,
    val tmin: String? = null,
    val hmax: String? = null,
    val hmin: String? = null,
)
