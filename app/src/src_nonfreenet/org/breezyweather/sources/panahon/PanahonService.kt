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

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.wrappers.WeatherWrapper
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.common.exceptions.InvalidLocationException
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Named

class PanahonService @Inject constructor(
    @Named("JsonClient") client: Retrofit.Builder,
) : PanahonServiceStub() {

    private val api by lazy {
        client.baseUrl("https://api.mobile.panahon.gov.ph/").build().create(PanahonApi::class.java)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        if (requestedFeatures.none { it in supportedFeatures }) return Observable.just(WeatherWrapper())
        if (!location.countryCode.equals("PH", ignoreCase = true)) return Observable.error(InvalidLocationException())

        return api.getDashboard(location.latitude, location.longitude).map {
            if (it.success && !it.meta.matches(location.latitude, location.longitude)) throw InvalidLocationException()
            it.toWeather(requestedFeatures, System.currentTimeMillis())
        }
    }
}
