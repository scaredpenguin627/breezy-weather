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

package org.breezyweather.sources.emb

import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import org.breezyweather.common.serializer.DateSerializer
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.Date

internal interface EmbApi {
    @GET
    fun getAirQuality(@Url url: HttpUrl): Observable<EmbReport>
}

@Serializable
internal data class EmbReport(
    @Serializable(DateSerializer::class) val fetchedAt: Date,
    val dataClock: String? = null,
    val stations: List<EmbStation>,
)

@Serializable
internal data class EmbStation(
    val name: String,
    // Maximum of the station's PM10 and PM2.5 subindices. Neither value is a concentration.
    val aqi: Int? = null,
)
