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
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Query

internal interface PanahonApi {
    @Headers("Accept: application/json")
    @GET("api/v1/cap-alerts")
    fun getAlerts(): Observable<PanahonAlertResponse>
}

internal interface PanahonMapApi {
    @GET("/")
    fun getMap(): Observable<Response<ResponseBody>>

    @GET("/api/v1/tiles/point/forecast?model=ecmwf")
    fun getPointForecast(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("init") initialization: String,
        @Query("token") token: String,
        @Header("Cookie") cookie: String?,
    ): Observable<PanahonPointReport>

    @GET("/api/v1/tiles/point?model=ecmwf")
    fun getPointValue(
        @Query("url") layer: String,
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("t") localTime: String,
        @Query("init") initialization: String,
        @Query("token") token: String,
        @Header("Cookie") cookie: String?,
    ): Observable<PanahonMapValue>
}
