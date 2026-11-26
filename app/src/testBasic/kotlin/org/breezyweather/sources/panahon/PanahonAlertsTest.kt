/*
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.panahon

import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.reference.AlertSeverity
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.breezyweather.R
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.Date

class PanahonAlertsTest {
    private val boracay =
        Location(city = "Boracay Island", countryCode = "PH", latitude = 11.98013, longitude = 121.91932)
    private val metadata = PanahonCoordinates("11.9801", "121.9193", municipality = "Malay", province = "Aklan")
    private val now = Instant.parse("2026-11-26T20:30:00Z").toEpochMilli()
    private val feed = Json { ignoreUnknownKeys = true }.decodeFromString<PanahonAlertResponse>(
        """
        {
          "success": true,
          "data": {
            "alert_count": 3,
            "alert_data": [
              {"event": "NOTE", "headline": "<script>location='https://example.invalid'</script>"},
              {
                "identifier": "VISPRSD_thunderstorm_watch_1795701600",
                "event": "THUNDERSTORM", "subtype": "thunderstorm watch",
                "headline": "Thunderstorm Watch #VISPRSD", "published_by": "VISPRSD",
                "message": "Thunderstorm Watch #VISPRSD\nMonitor news & alerts <official>.",
                "issued_date": "2026-11-26 22:00:00", "valid_date": "2026-11-27 10:00:00",
                "provinces": [
                  {
                    "province": "Aklan", "province_fallback": true, "type": "expecting",
                    "shape": "[[[121.8,11.4],[122.3,11.4],[122.3,11.92],[121.8,11.92]]]"
                  }
                ]
              },
              {
                "identifier": "VISPRSD_thunderstorm_advisory_2_1795724520",
                "event": "THUNDERSTORM", "subtype": "thunderstorm advisory", "published_by": "VISPRSD",
                "message": "Thunderstorm Advisory No. 2 #VISPRSD\nStrong winds reported in Batan.",
                "issued_date": "2026-11-27 04:22:00", "valid_date": "2026-11-27 06:22:00",
                "provinces": [
                  {
                    "municipality": "Batan", "province": "Aklan", "type": "affecting",
                    "shape": "[[[[11.5,122.4],[11.5,122.6],[11.7,122.6],[11.7,122.4]]]]"
                  }
                ]
              },
              {"event": "THUNDERSTORM", "subtype": "thunderstorm information"}
            ]
          }
        }
        """.trimIndent()
    )

    @Test
    fun matchesProvinceWatchAcrossIslandGeometryAndExcludesOtherMunicipalities() {
        val alert = feed.toAlerts(boracay, metadata, now, ::messageFor).alertList!!.single()
        alert.headline shouldBe "雷暴关注 · 阿克兰省"
        alert.alertId shouldBe "panahon:VISPRSD_thunderstorm_watch_1795701600"
        alert.source shouldBe "PAGASA PANaHON · VISPRSD"
        alert.description shouldBe "Thunderstorm Watch #VISPRSD\nMonitor news &amp; alerts &lt;official&gt;."
        alert.startDate shouldBe Date.from(Instant.parse("2026-11-26T14:00:00Z"))
        alert.endDate shouldBe Date.from(Instant.parse("2026-11-27T02:00:00Z"))
        alert.severity shouldBe AlertSeverity.MINOR
    }

    @Test
    fun ranksLocalAdvisoryAboveWatchWhileKeepingTheirOwnScope() {
        val batan = Location(city = "Batan", countryCode = "PH", latitude = 11.63, longitude = 122.49)
        val localMetadata = PanahonCoordinates("11.63", "122.49", municipality = "Batan", province = "Aklan")
        val result = feed.toAlerts(batan, localMetadata, now, ::messageFor)
        result.alertList?.map { it.headline } shouldBe listOf("雷暴警报 · Batan", "雷暴关注 · 阿克兰省")
        result.alertList?.first()?.severity shouldBe AlertSeverity.MODERATE
    }

    @Test
    fun usesGeometryWhenDashboardLocationIsMissingOrDoesNotMatch() {
        val batan = Location(
            city = "Batan",
            countryCode = "PH",
            latitude = 11.63,
            longitude = 122.49,
            admin2 = "阿克兰省",
            admin3 = "Malinao"
        )
        val unrelatedMetadata = PanahonCoordinates("14.6", "120.98", municipality = "Manila", province = "Metro Manila")
        val result = feed.toAlerts(batan, unrelatedMetadata, now, ::messageFor)
        result.alertList?.map { it.headline } shouldBe listOf("雷暴警报 · Batan")
        val island = feed.toAlerts(boracay, unrelatedMetadata, now, ::messageFor)
        island.alertList shouldBe null
        (island.failedFeatures?.get(SourceFeature.ALERT) is InvalidOrIncompleteDataException) shouldBe true
    }

    @Test
    fun usesPolygonFootprintWhenProvinceIsOnlyMetadata() {
        val alerts = feed.data!!.alerts!!.toMutableList()
        val watch = alerts[1]
        alerts[1] = watch.copy(areas = watch.areas!!.map { it.copy(provinceFallback = false) })
        val polygon = feed.copy(data = feed.data.copy(alerts = alerts))
        polygon.toAlerts(boracay, metadata, now, ::messageFor).alertList shouldBe emptyList()
    }

    @Test
    fun filtersOutUnissuedAndExpiredAlertsInPhilippineTime() {
        feed.toAlerts(
            boracay,
            metadata,
            Instant.parse("2026-11-26T13:59:59Z").toEpochMilli(),
            ::messageFor
        ).alertList shouldBe emptyList()
        feed.toAlerts(
            boracay,
            metadata,
            Instant.parse("2026-11-27T02:00:00Z").toEpochMilli(),
            ::messageFor
        ).alertList shouldBe emptyList()
    }

    @Test
    fun retainsLocalAlertWhenAnotherRecordIsIncomplete() {
        val response = feed.data!!
        val records = response.alerts!! + PanahonAlert(
            event = "FLOOD",
            issuedAt = "2026-11-27 04:00:00",
            validUntil = "2026-11-27 05:00:00",
            areas = listOf(PanahonAlertArea(shape = JsonPrimitive("unrecognized geometry")))
        )
        val partial = feed.copy(data = response.copy(count = 4, alerts = records))
        val result = partial.toAlerts(boracay, metadata, now, ::messageFor)
        result.alertList?.map { it.headline } shouldBe listOf("雷暴关注 · 阿克兰省")
        (result.failedFeatures?.get(SourceFeature.ALERT) is InvalidOrIncompleteDataException) shouldBe true
    }

    @Test
    fun reportsIncompleteFeedOrUnresolvableAreaWithoutClearingCache() {
        val incomplete = PanahonAlertResponse(true, PanahonAlertData(1, emptyList()))
        val truncated = incomplete.toAlerts(boracay, metadata, now, ::messageFor)
        truncated.alertList shouldBe null
        (truncated.failedFeatures?.get(SourceFeature.ALERT) is InvalidOrIncompleteDataException) shouldBe true
        val areas = listOf(PanahonAlertArea(shape = JsonPrimitive("not a polygon")))
        val warning = feed.data!!.alerts!![1].copy(areas = areas)
        val unresolved = PanahonAlertResponse(true, PanahonAlertData(1, listOf(warning)))
            .toAlerts(boracay, metadata, now, ::messageFor)
        unresolved.alertList shouldBe null
        (unresolved.failedFeatures?.get(SourceFeature.ALERT) is InvalidOrIncompleteDataException) shouldBe true
    }

    @Test
    fun preservesLocalAlertsWhenForecastFailsValidation() {
        val result = combinePanahonWeather(
            Result.success(PanahonResponse(true, metadata)),
            Result.success(feed),
            listOf(SourceFeature.FORECAST, SourceFeature.CURRENT),
            boracay,
            now,
            ::messageFor
        )
        result.alertList?.single()?.headline shouldBe "雷暴关注 · 阿克兰省"
        result.failedFeatures?.keys shouldBe setOf(SourceFeature.FORECAST, SourceFeature.CURRENT)
    }

    @Test
    fun preservesWeatherAndReportsAlertFailureWithoutReturningEmptyAlerts() {
        val dashboard = PanahonResponse(
            true,
            metadata,
            PanahonDashboard(
                nc = PanahonHour(time = "4:00 AM", tmp = "28 °C"),
                hc = listOf(PanahonHour(time = "4:00 AM", tmp = "28 °C")),
                dc = listOf(PanahonDay(dt = "11/27", tmax = "33 °C"))
            )
        )
        val networkError = IOException("Alert feed unavailable")
        val result = combinePanahonWeather(
            Result.success(dashboard),
            Result.failure(networkError),
            listOf(SourceFeature.FORECAST, SourceFeature.CURRENT),
            boracay,
            now,
            ::messageFor
        )
        result.alertList shouldBe null
        result.failedFeatures shouldBe mapOf(SourceFeature.ALERT to networkError)
        result.dailyForecast?.size shouldBe 1
        result.current?.temperature?.temperature?.inCelsius shouldBe 28.0
    }

    private fun messageFor(resourceId: Int) = when (resourceId) {
        R.string.panahon_thunderstorm_watch -> "雷暴关注"
        R.string.panahon_thunderstorm_advisory -> "雷暴警报"
        R.string.panahon_alert_aklan -> "阿克兰省"
        else -> error("Unexpected string resource $resourceId")
    }
}
