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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.breezyweather.common.exceptions.InvalidLocationException
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.extensions.getStringByLocale
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class PanahonService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : PanahonServiceStub(context) {

    private val retrofit by lazy { client.baseUrl("https://api.mobile.panahon.gov.ph/").build() }
    private val api by lazy { retrofit.create(PanahonApi::class.java) }
    private val mapApi by lazy {
        // The map's guest token and cookies must stay off disk, out of logs and on this origin.
        val mapClient = (retrofit.callFactory() as OkHttpClient).newBuilder().apply {
            interceptors.removeAll { it is HttpLoggingInterceptor }
            networkInterceptors.removeAll { it is HttpLoggingInterceptor }
        }.cache(null).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build()
        retrofit.newBuilder().baseUrl("https://panahon.gov.ph/").client(mapClient).build()
            .create(PanahonMapApi::class.java)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        val features = requestedFeatures.filter { it in supportedFeatures }
        if (features.isEmpty()) return Observable.just(WeatherWrapper())
        if (!location.countryCode.equals("PH", ignoreCase = true)) return Observable.error(InvalidLocationException())

        val dashboard = api.getDashboard(location.latitude, location.longitude).map {
            if (it.success && !it.meta.matches(location.latitude, location.longitude)) throw InvalidLocationException()
            it
        }
        val weather = if (SourceFeature.ALERT !in features) {
            dashboard.map { it.toWeather(features, System.currentTimeMillis()) }
        } else {
            val weatherFeatures = features.filterNot { it == SourceFeature.ALERT }
            val locale = context.currentLocale
            Observable.zip(dashboard.asResult(), api.getAlerts().asResult()) { weatherResult, alertResult ->
                combinePanahonWeather(
                    weatherResult,
                    alertResult,
                    weatherFeatures,
                    location,
                    System.currentTimeMillis()
                ) { context.getStringByLocale(it, locale) }
            }
        }
        if (SourceFeature.FORECAST !in features) return weather

        val wind = mapApi.getMap()
            .flatMap { response ->
                val session = response.toPanahonWindSession()
                mapApi.getForecastWind(
                    location.latitude,
                    location.longitude,
                    session.initialization.toString(),
                    session.token,
                    session.cookie
                ).map { it to session.initialization }
            }
            .timeout(12, TimeUnit.SECONDS)
            .asResult()
        return Observable.zip(weather.subscribeOn(Schedulers.io()), wind) { native, result ->
            result.getOrNull()?.let { (report, initialization) -> native.withForecastWind(report, initialization) }
                ?: native
        }
    }
}

private fun <T : Any> Observable<T>.asResult(): Observable<Result<T>> =
    subscribeOn(Schedulers.io()).map { Result.success(it) }.onErrorReturn { Result.failure(it) }

internal fun combinePanahonWeather(
    dashboard: Result<PanahonResponse>,
    feed: Result<PanahonAlertResponse>,
    weatherFeatures: List<SourceFeature>,
    location: Location,
    now: Long,
    getString: (Int) -> String,
): WeatherWrapper {
    val weather = if (weatherFeatures.isEmpty()) {
        WeatherWrapper()
    } else {
        dashboard.mapCatching { it.toWeather(weatherFeatures, now) }.getOrElse { error ->
            WeatherWrapper(failedFeatures = weatherFeatures.associateWith { error })
        }
    }
    val metadata = dashboard.getOrNull()?.takeIf { it.success }?.meta
    val alerts = feed.mapCatching { it.toAlerts(location, metadata, now, getString) }.getOrElse {
        WeatherWrapper(failedFeatures = mapOf(SourceFeature.ALERT to it))
    }
    return weather.copy(
        alertList = alerts.alertList,
        failedFeatures = weather.failedFeatures.orEmpty() + alerts.failedFeatures.orEmpty()
    )
}
