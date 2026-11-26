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

import android.content.Context
import androidx.compose.ui.text.input.KeyboardType
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.AirQuality
import breezyweather.domain.weather.wrappers.AirQualityWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.breezyweather.R
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.breezyweather.common.exceptions.UnsupportedFeatureException
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.domain.settings.SourceConfigStore
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class EmbService @Inject constructor(
    @ApplicationContext context: Context,
    @Named("JsonClient") jsonClient: Retrofit.Builder,
) : EmbServiceStub() {

    override val privacyPolicyUrl =
        "https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement"
    override val attributionLinks = mapOf("DENR-EMB NCR" to "https://ncr.emb.gov.ph/realtimeairmonitoring/")

    private val api by lazy {
        jsonClient.baseUrl("https://raw.githubusercontent.com/").build().create(EmbApi::class.java)
    }
    private val config = SourceConfigStore(context, id)
    private val endpoint: String
        get() = config.getString("endpoint", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
    override val isConfigured: Boolean
        get() = endpoint.toHttpUrlOrNull()?.isHttps == true

    override fun getPreferences(context: Context): List<Preference> = listOf(
        EditTextPreference(
            titleId = R.string.settings_weather_source_emb_endpoint,
            content = config.getString("endpoint", null),
            placeholder = DEFAULT_ENDPOINT,
            summary = { _, content -> content.ifBlank { DEFAULT_ENDPOINT } },
            regex = Regex("""https://[^\s/]+/[^\s]+""", RegexOption.IGNORE_CASE),
            regexError = context.getString(R.string.settings_weather_source_emb_endpoint_hint),
            keyboardType = KeyboardType.Uri,
            onValueChanged = { config.edit().putString("endpoint", it.trim()).apply() }
        )
    )

    override fun requestWeather(
        context: Context,
        location: Location,
        requestedFeatures: List<SourceFeature>,
    ): Observable<WeatherWrapper> {
        if (SourceFeature.AIR_QUALITY !in requestedFeatures ||
            !isFeatureSupportedForLocation(location, SourceFeature.AIR_QUALITY)
        ) {
            return Observable.error(UnsupportedFeatureException())
        }
        val url = endpoint.toHttpUrlOrNull()?.takeIf { it.isHttps }
            ?: return Observable.error(InvalidOrIncompleteDataException())
        return api.getAirQuality(url).map { report ->
            WeatherWrapper(airQuality = report.toAirQuality(location, System.currentTimeMillis()))
        }
    }

    companion object {
        private const val DEFAULT_ENDPOINT =
            "https://raw.githubusercontent.com/scaredpenguin627/breezy-weather/emb-data/aqi.json"
    }
}

internal fun EmbReport.toAirQuality(location: Location, nowMillis: Long): AirQualityWrapper {
    // The proxy can serve its last cached response when the upstream is unavailable.
    if (nowMillis - fetchedAt.time > TimeUnit.DAYS.toMillis(1)) {
        throw OutdatedServerDataException()
    }
    val observations = stations.mapNotNull { station ->
        station.aqi?.takeIf { it >= 0 }?.let { station.name to it }
    }.toMap()
    val nearest = EmbServiceStub.nearestStation(location, observations.keys) ?: throw InvalidOrIncompleteDataException()
    return AirQualityWrapper(
        current = AirQuality(
            reportedAqi = observations.getValue(nearest),
            reportedStation = nearest,
            reportedClock = dataClock?.takeIf { it.isNotBlank() }
        ),
        fetchedAt = fetchedAt
    )
}
