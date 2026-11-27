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

import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.NonFreeNetSource
import org.breezyweather.common.source.WeatherSource
import org.breezyweather.common.source.WeatherSource.Companion.PRIORITY_HIGH
import org.breezyweather.common.source.WeatherSource.Companion.PRIORITY_HIGHEST
import org.breezyweather.common.source.WeatherSource.Companion.PRIORITY_NONE
import java.util.TimeZone

abstract class PanahonServiceStub : HttpSource(), WeatherSource, NonFreeNetSource {

    override val id = Location.PANAHON_SOURCE
    override val name = "PANaHON (PAGASA)"
    override val continent = SourceContinent.ASIA
    override val privacyPolicyUrl = ""

    private val attribution = "PAGASA PANaHON"
    override val attributionLinks = mapOf(attribution to "https://www.panahon.gov.ph/")
    override val supportedFeatures = mapOf(
        SourceFeature.FORECAST to attribution,
        SourceFeature.CURRENT to attribution,
        SourceFeature.ALERT to attribution
    )

    override fun isFeatureSupportedForLocation(location: Location, feature: SourceFeature): Boolean =
        feature in supportedFeatures && location.countryCode.equals("PH", ignoreCase = true)

    override fun getFeaturePriorityForLocation(location: Location, feature: SourceFeature): Int =
        when {
            !isFeatureSupportedForLocation(location, feature) -> PRIORITY_NONE
            feature == SourceFeature.ALERT -> PRIORITY_HIGH
            else -> PRIORITY_HIGHEST
        }

    override val testingLocations = listOf(
        Location(
            city = "Manila",
            latitude = 14.5995,
            longitude = 120.9842,
            timeZone = TimeZone.getTimeZone("Asia/Manila"),
            countryCode = "PH",
            forecastSourcePreference = id,
            currentSourcePreference = id,
            alertSource = id
        )
    )
}
