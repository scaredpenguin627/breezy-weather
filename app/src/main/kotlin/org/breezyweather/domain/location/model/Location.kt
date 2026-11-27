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

package org.breezyweather.domain.location.model

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.location.model.Location.Companion.CLOSE_DISTANCE
import breezyweather.domain.source.SourceFeature
import com.google.maps.android.SphericalUtil
import com.google.maps.android.model.LatLng
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.domain.settings.SettingsManager
import org.breezyweather.domain.weather.model.getRiseProgress
import org.breezyweather.sources.SourceManager

fun Location.getPlace(context: Context, showCurrentPositionInPriority: Boolean = false): String {
    if (showCurrentPositionInPriority && isCurrentPosition) {
        return context.getString(R.string.location_current)
    }
    if (!customName.isNullOrEmpty()) {
        return customName!!
    }
    if (cityAndDistrict.isNotEmpty()) {
        return cityAndDistrict
    }
    if (cityAndDistrict.isEmpty() && isCurrentPosition) {
        return context.getString(R.string.location_current)
    }
    return "$latitude, $longitude"
}

val Location.isDaylight: Boolean
    get() {
        val sunRiseProgress = getRiseProgress(
            astro = this.weather?.today?.sun,
            location = this
        )
        return 0 < sunRiseProgress && sunRiseProgress < 1
    }

fun Location.applyWeatherSourcePreference(context: Context, sourceManager: SourceManager): Location {
    val preference = SettingsManager.getInstance(context).defaultForecastSource
    if (preference == "auto") return applyDefaultPreset(sourceManager)
    val source = sourceManager.getWeatherSource(preference) ?: return applyDefaultPreset(sourceManager)
    fun preferred(feature: SourceFeature): String? = source.id.takeIf {
        feature in source.supportedFeatures && source.isFeatureSupportedForLocation(this, feature)
    }
    return copy(
        forecastSourcePreference = source.id,
        currentSourcePreference = preferred(SourceFeature.CURRENT),
        airQualitySource = preferred(SourceFeature.AIR_QUALITY),
        pollenSource = preferred(SourceFeature.POLLEN),
        minutelySource = preferred(SourceFeature.MINUTELY),
        alertSource = preferred(SourceFeature.ALERT)
    )
}

fun Location.applyDefaultPreset(sourceManager: SourceManager): Location {
    val forecastSource = sourceManager.getBestSourceForFeatureOrDefault(this, SourceFeature.FORECAST)!!.id

    val preset = copy(
        forecastSourcePreference = forecastSource,
        currentSourcePreference = sourceManager.getBestSourceForFeature(this, SourceFeature.CURRENT)?.id
            ?: sourceManager.getDefaultSourceForFeature(this, SourceFeature.CURRENT)?.id?.let {
                // If current source is the default (Open-Meteo), let it be null to fallback to forecast,
                // instead of using Open-Meteo "forecast" as current, which would make it inconsistent
                if (it != forecastSource) null else it
            },
        airQualitySource = sourceManager.getBestSourceForFeatureOrDefault(this, SourceFeature.AIR_QUALITY)?.id,
        pollenSource = sourceManager.getBestSourceForFeatureOrDefault(this, SourceFeature.POLLEN)?.id,
        minutelySource = sourceManager.getBestSourceForFeatureOrDefault(this, SourceFeature.MINUTELY)?.id,
        alertSource = sourceManager.getBestSourceForFeatureOrDefault(this, SourceFeature.ALERT)?.id,
        reverseGeocodingSource = sourceManager
            .getBestSourceForFeatureOrDefault(this, SourceFeature.REVERSE_GEOCODING)?.id
    )
    return if (BuildConfig.FLAVOR != "freenet" && isCurrentPosition && !isUsable) {
        preset.copy(
            forecastSourcePreference = Location.automaticGpsPreference(preset.forecastSource),
            currentSourcePreference = Location.automaticGpsPreference(preset.currentSource),
            automaticPhSource = Location.PANAHON_SOURCE
        )
    } else {
        preset
    }
}

fun Location.forecastOrCurrentSourceChanged(other: Location): Boolean =
    forecastSource != other.forecastSource || currentSource != other.currentSource

fun Location.isCloseTo(location: Location): Boolean {
    return cityId == location.cityId ||
        SphericalUtil.computeDistanceBetween(
            LatLng(location.latitude, location.longitude),
            LatLng(latitude, longitude)
        ) < CLOSE_DISTANCE
}
