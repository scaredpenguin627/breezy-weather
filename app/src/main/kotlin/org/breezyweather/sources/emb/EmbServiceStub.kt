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

import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceContinent
import breezyweather.domain.source.SourceFeature
import com.google.maps.android.model.LatLng
import org.breezyweather.common.source.ConfigurableSource
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.NonFreeNetSource
import org.breezyweather.common.source.WeatherSource
import org.breezyweather.common.source.WeatherSource.Companion.PRIORITY_HIGH
import org.breezyweather.common.source.WeatherSource.Companion.PRIORITY_NONE

abstract class EmbServiceStub : HttpSource(), WeatherSource, ConfigurableSource, NonFreeNetSource {

    override val id = "emb"
    override val name = "DENR-EMB (Metro Manila)"
    override val continent = SourceContinent.ASIA
    override val supportedFeatures = mapOf(SourceFeature.AIR_QUALITY to "DENR-EMB NCR")
    override val isRestricted = false

    override fun isFeatureSupportedForLocation(location: Location, feature: SourceFeature): Boolean =
        feature == SourceFeature.AIR_QUALITY && location.countryCode.equals("PH", ignoreCase = true) &&
            nearestStation(location) != null

    override fun getFeaturePriorityForLocation(location: Location, feature: SourceFeature): Int =
        if (isFeatureSupportedForLocation(location, feature)) PRIORITY_HIGH else PRIORITY_NONE

    companion object {
        internal fun nearestStation(location: Location, reportingStations: Set<String>? = null): String? {
            val candidates = reportingStations?.let { names -> STATIONS.filterKeys { it in names } } ?: STATIONS
            return LatLng(location.latitude, location.longitude).getNearestLocation(candidates, 10_000.0)
        }

        // DENR NCR 2023 Regional Statistical Profile, "Location of AQMS", pp. 111–112:
        // https://ncr.denr.gov.ph/wp-content/uploads/2024/01/2023-RSP.pdf
        // Navotas City Hall: EMB NCR 2020 State of Brown Environment, table 2.
        // Match report names exactly; only geolocated sites can be used as nearby observations.
        private val STATIONS = mapOf(
            "Caloocan (Univ. of East)" to LatLng(14.65865, 120.976307),
            "Las Piñas" to LatLng(14.4501, 120.9944),
            "Makati" to LatLng(14.5644, 121.0577),
            "Mandaluyong" to LatLng(14.5834, 121.0368),
            "Manila" to LatLng(14.5917, 120.9806),
            "Marikina CEMO" to LatLng(14.640142, 121.103452),
            "Muntinlupa (Bilibid)" to LatLng(14.3835, 121.0337),
            "Muntinlupa (Filinvest City)" to LatLng(14.414346, 121.044162),
            "Navotas" to LatLng(14.657717, 120.947700),
            "Parañaque (Don Bosco)" to LatLng(14.4815, 121.0383),
            "Pasay" to LatLng(14.5283, 121.0081),
            "Quezon City (Ateneo)" to LatLng(14.6357, 121.0785),
            "San Juan City" to LatLng(14.6056, 121.0322),
            "Taguig (TUP)" to LatLng(14.5105, 121.0359)
        )
    }
}
