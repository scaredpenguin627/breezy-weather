/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.panahon

import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.model.Alert
import breezyweather.domain.weather.reference.AlertSeverity
import breezyweather.domain.weather.wrappers.WeatherWrapper
import com.google.maps.android.PolyUtil
import com.google.maps.android.model.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.breezyweather.R
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale

@Serializable
internal data class PanahonAlertResponse(val success: Boolean, val data: PanahonAlertData? = null)

@Serializable
internal data class PanahonAlertData(
    @SerialName("alert_count") val count: Int? = null,
    @SerialName("alert_data") val alerts: List<PanahonAlert>? = null,
)

@Serializable
internal data class PanahonAlert(
    val identifier: String? = null,
    val event: String? = null,
    val subtype: String? = null,
    val headline: String? = null,
    val message: String? = null,
    @SerialName("optional_message") val instruction: String? = null,
    @SerialName("issued_date") val issuedAt: String? = null,
    @SerialName("valid_date") val validUntil: String? = null,
    @SerialName("published_by") val publisher: String? = null,
    @SerialName("provinces") val areas: List<PanahonAlertArea>? = null,
)

@Serializable
internal data class PanahonAlertArea(
    val municipality: String? = null,
    val province: String? = null,
    val shape: JsonElement? = null,
    @SerialName("type") val level: String? = null,
    @SerialName("province_fallback") val provinceFallback: Boolean = false,
)

private val manilaZone = ZoneId.of("Asia/Manila")
private val alertDateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun PanahonAlertResponse.toAlerts(
    location: Location,
    metadata: PanahonCoordinates?,
    now: Long,
    getString: (Int) -> String,
): WeatherWrapper {
    if (!success || data == null) throw InvalidOrIncompleteDataException()
    val received = data.alerts ?: if (data.count == 0) emptyList() else throw InvalidOrIncompleteDataException()
    // The map feed includes a NOTE record with presentation metadata alongside its counted warnings.
    val records = received.filterNot { it.event.equals("NOTE", ignoreCase = true) }
    var incomplete = data.count != null && data.count != records.size

    val areaMetadata = metadata?.takeIf { it.matches(location.latitude, location.longitude) }
    val province = areaMetadata?.province.provinceKey()
        ?: areaMetadata?.region.provinceKey()?.takeIf { it == "metromanila" }
    val municipality = areaMetadata?.municipality.municipalityKey()
    val point = LatLng(location.latitude, location.longitude)
    val alerts = records.mapNotNull { record ->
        try {
            record.toAlert(point, province, municipality, now, getString)
        } catch (_: InvalidOrIncompleteDataException) {
            incomplete = true
            null
        }
    }.distinctBy { it.alertId }
        .sortedWith(compareByDescending<Alert> { it.severity.id }.thenByDescending { it.startDate })
    return WeatherWrapper(
        alertList = alerts.takeUnless { it.isEmpty() && incomplete },
        failedFeatures = if (incomplete) mapOf(SourceFeature.ALERT to InvalidOrIncompleteDataException()) else null
    )
}

private fun PanahonAlert.toAlert(
    point: LatLng,
    province: String?,
    municipality: String?,
    now: Long,
    getString: (Int) -> String,
): Alert? {
    val kind = subtype?.trim()?.lowercase(Locale.ROOT)
    if (kind == "thunderstorm information") return null
    val start = issuedAt.toAlertTime()
    val end = validUntil.toAlertTime()
    if (start >= end) throw InvalidOrIncompleteDataException()
    if (now < start || now >= end) return null

    val publishedAreas = areas?.takeIf { it.isNotEmpty() } ?: throw InvalidOrIncompleteDataException()
    val matches = publishedAreas.map { it to it.matches(point, province, municipality) }
    val area = matches.filter { it.second == true }
        .map { it.first }
        .maxByOrNull { it.severity(kind).id }
    if (area == null) {
        if (matches.any { it.second == null }) throw InvalidOrIncompleteDataException()
        return null
    }
    val severity = area.severity(kind)
    val scope = area.municipality?.takeIf { it.isNotBlank() } ?: area.province?.takeIf { it.isNotBlank() }?.let {
        when (it.provinceKey()) {
            "aklan" -> getString(R.string.panahon_alert_aklan)
            "metromanila" -> getString(R.string.panahon_alert_metro_manila)
            else -> getString(R.string.panahon_alert_province).format(it)
        }
    }
    val title = when (kind) {
        "thunderstorm watch" -> getString(R.string.panahon_thunderstorm_watch)
        "thunderstorm advisory" -> getString(R.string.panahon_thunderstorm_advisory)
        "rainfall advisory", "rainfall warning" -> getString(R.string.panahon_rainfall_warning)
        "tropical cyclone warning" -> getString(R.string.panahon_cyclone_warning)
        else -> when (event?.uppercase(Locale.ROOT)) {
            "FLOOD" -> getString(R.string.panahon_flood_advisory)
            else -> headline?.trim()?.takeIf { it.isNotEmpty() } ?: throw InvalidOrIncompleteDataException()
        }
    }
    val id = identifier?.takeIf { it.isNotBlank() } ?: throw InvalidOrIncompleteDataException()
    val original = message?.trim()?.takeIf { it.isNotEmpty() } ?: throw InvalidOrIncompleteDataException()
    return Alert(
        alertId = "panahon:$id",
        startDate = Date(start),
        endDate = Date(end),
        headline = listOfNotNull(title, scope).joinToString(" · "),
        // Alert descriptions are rendered as HTML. PANaHON bulletins are plain text.
        description = original.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"),
        instruction = instruction?.trim()?.takeIf { it.isNotEmpty() && it !in original },
        source = listOfNotNull(
            "PAGASA PANaHON",
            publisher?.trim()?.takeUnless { it.isEmpty() || it.equals("PAGASA", ignoreCase = true) }
        ).joinToString(" · "),
        severity = severity,
        color = Alert.colorFromSeverity(severity)
    )
}

private fun String?.toAlertTime(): Long {
    if (this == null) throw InvalidOrIncompleteDataException()
    return try {
        LocalDateTime.parse(this, alertDateFormat).atZone(manilaZone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        throw InvalidOrIncompleteDataException()
    }
}

private fun String?.provinceKey(): String? {
    val key = this?.trim()?.lowercase(Locale.ROOT)?.removePrefix("province of ")
        ?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
    return when (key) {
        "nationalcapitalregion", "ncr" -> "metromanila"
        "阿克兰", "阿克兰省", "阿克蘭", "阿克蘭省" -> "aklan"
        else -> key
    }
}

private fun String?.municipalityKey(): String? = this?.trim()?.lowercase(Locale.ROOT)
    ?.removePrefix("city of ")?.removePrefix("municipality of ")?.removeSuffix(" city")
    ?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }

private fun PanahonAlertArea.matches(point: LatLng, provinceKey: String?, municipalityKey: String?): Boolean? {
    val areaProvince = province.provinceKey()
    val areaMunicipality = municipality.municipalityKey()
    if (areaProvince != null && areaMunicipality == null && (provinceFallback || shape == null)) {
        // The province boundaries distributed by PANaHON may omit outlying islands such as Boracay.
        return provinceKey?.let { it == areaProvince } ?: shape.containsPoint(point)?.takeIf { it }
    }
    if (areaMunicipality != null && areaProvince != null && provinceKey != null && municipalityKey != null) {
        return areaProvince == provinceKey && areaMunicipality == municipalityKey
    }
    return shape.containsPoint(point)
}

private fun PanahonAlertArea.severity(subtype: String?): AlertSeverity = when (subtype) {
    "thunderstorm watch" -> AlertSeverity.MINOR
    "thunderstorm advisory" -> AlertSeverity.MODERATE
    else -> when (level?.trim()?.lowercase(Locale.ROOT)) {
        "red", "emergency", "extreme" -> AlertSeverity.EXTREME
        "orange", "severe" -> AlertSeverity.SEVERE
        "yellow", "moderate" -> AlertSeverity.MODERATE
        "rainfall advisory" -> AlertSeverity.MINOR
        else -> AlertSeverity.UNKNOWN
    }
}

private fun JsonElement?.containsPoint(point: LatLng): Boolean? {
    val coordinates = when (this) {
        is JsonArray -> this
        is JsonPrimitive -> try {
            Json.parseToJsonElement(content) as? JsonArray
        } catch (_: SerializationException) {
            null
        }
        else -> null
    } ?: return null

    // Province areas use polygon coordinates. Municipalities can use multipolygons.
    coordinates.toRing()?.let { return PolyUtil.containsLocation(point, it, false) }
    coordinates.polygonContains(point)?.let { return it }
    if (coordinates.isEmpty()) return null
    val results = coordinates.map { (it as? JsonArray)?.polygonContains(point) }
    return when {
        true in results -> true
        null in results -> null
        else -> false
    }
}

private fun JsonArray.polygonContains(point: LatLng): Boolean? {
    val rings = map { (it as? JsonArray)?.toRing() ?: return null }
    val exterior = rings.firstOrNull() ?: return null
    return PolyUtil.containsLocation(point, exterior, false) &&
        rings.drop(1).none { PolyUtil.containsLocation(point, it, false) }
}

private fun JsonArray.toRing(): List<LatLng>? {
    if (size < 3) return null
    return map {
        val pair = it as? JsonArray ?: return null
        val first = (pair.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val second = (pair.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        // PANaHON supplies municipality shapes as lat/lon and province shapes as lon/lat.
        when {
            first in 0.0..30.0 && second in 110.0..140.0 -> LatLng(first, second)
            second in 0.0..30.0 && first in 110.0..140.0 -> LatLng(second, first)
            else -> return null
        }
    }
}
