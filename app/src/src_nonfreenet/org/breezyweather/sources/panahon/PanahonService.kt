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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.breezyweather.common.exceptions.InvalidLocationException
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.extensions.getStringByLocale
import retrofit2.Retrofit
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class PanahonService @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    @Named("JsonClient") client: Retrofit.Builder,
) : PanahonServiceStub() {

    private val retrofit by lazy { client.baseUrl("https://panahon.gov.ph/").build() }
    private val tiles by lazy {
        PanahonPointCache.forDirectory(File(applicationContext.cacheDir, "panahon-tiles"))
    }
    private val api by lazy { retrofit.create(PanahonApi::class.java) }
    private val mapApi by lazy {
        // The map's guest token and cookies must stay off disk, out of logs and on this origin.
        val mapClient = (retrofit.callFactory() as OkHttpClient).newBuilder().apply {
            interceptors().removeAll { it is HttpLoggingInterceptor }
            networkInterceptors().removeAll { it is HttpLoggingInterceptor }
        }.dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
            .cache(null).cookieJar(CookieJar.NO_COOKIES).followRedirects(false).build()
        retrofit.newBuilder().client(mapClient).build().create(PanahonMapApi::class.java)
    }

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        val features = requestedFeatures.filter { it in supportedFeatures }
        if (features.isEmpty()) return Observable.just(WeatherWrapper())
        if (!location.countryCode.equals("PH", ignoreCase = true)) return Observable.error(InvalidLocationException())

        val locale = context.currentLocale
        val weatherFeatures = features.filterNot { it == SourceFeature.ALERT }
        val alerts = if (SourceFeature.ALERT in features) {
            api.getAlerts().map { response ->
                response.toAlerts(location, System.currentTimeMillis()) { context.getStringByLocale(it, locale) }
            }.onErrorReturn { WeatherWrapper(failedFeatures = mapOf(SourceFeature.ALERT to it)) }
        } else {
            Observable.just(WeatherWrapper())
        }
        if (weatherFeatures.isEmpty()) return alerts
        val weather = mapApi.getMap()
            .timeout(12, TimeUnit.SECONDS)
            .map { it.toPanahonMapSession() }
            .flatMap { session -> requestPointWeather(session, location, weatherFeatures) }
            .onErrorReturn { error -> WeatherWrapper(failedFeatures = weatherFeatures.associateWith { error }) }
        if (SourceFeature.ALERT !in features) return weather
        return Observable.zip(weather.subscribeOn(Schedulers.io()), alerts.subscribeOn(Schedulers.io())) {
                result,
                cap,
            ->
            result.copy(
                alertList = cap.alertList,
                failedFeatures =
                result.failedFeatures.orEmpty() + cap.failedFeatures.orEmpty()
            )
        }
    }
    private fun requestPointWeather(
        session: PanahonMapSession,
        location: Location,
        features: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        return mapApi.getPointForecast(
            location.latitude,
            location.longitude,
            session.initialization.toString(),
            session.token,
            session.cookie
        ).timeout(12, TimeUnit.SECONDS)
            .map { Result.success(it.toPointHours(session.initialization, System.currentTimeMillis())) }
            .onErrorReturn { Result.failure(it) }
            .flatMap { point ->
                val hours = point.getOrNull().orEmpty()
                val times = mutableSetOf<Long>()
                if (SourceFeature.FORECAST in features) hours.forEach { times.add(it.date.time) }
                if (SourceFeature.CURRENT in features) {
                    val hour = Instant.now().truncatedTo(ChronoUnit.HOURS)
                    val lead = ChronoUnit.HOURS.between(session.initialization, hour)
                    if (lead in 0L..144L && (lead <= 90 || lead % 3 == 0L)) times.add(hour.toEpochMilli())
                }
                Observable.zip(
                    tiles.get(mapApi, session, "temp", location.latitude, location.longitude, times)
                        .onErrorReturn { emptyMap<Long, Double>() },
                    tiles.get(mapApi, session, "rh", location.latitude, location.longitude, times)
                        .onErrorReturn { emptyMap<Long, Double>() }
                ) { temperatures, humidities -> temperatures to humidities }
                    .map { (temperatures, humidities) ->
                        val result = hours.toWeather(
                            temperatures,
                            humidities,
                            features,
                            System.currentTimeMillis()
                        )
                        val error = point.exceptionOrNull()
                        if (error != null) {
                            result.copy(failedFeatures = result.failedFeatures.orEmpty().mapValues { error })
                        } else {
                            result
                        }
                    }
            }
    }
}
