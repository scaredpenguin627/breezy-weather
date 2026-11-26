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

import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.reference.WeatherCode
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.breezyweather.common.exceptions.OutdatedServerDataException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

class PanahonConverterTest {

    private val response = Json { ignoreUnknownKeys = true }.decodeFromString<PanahonResponse>(
        """
        {
          "success": true,
          "data": {
            "nc": {"time":"11:00 PM","tmp":"28 °C","hindex":"31 °C","desc":"Partly cloudy skies"},
            "hc": [
              {"time":"11:00 PM","tmp":"28 °C","tprecip":"0 mm"},
              {"time":"12:00 AM","tmp":"27 °C","tprecip":"0.42 mm"},
              {"time":"1:00 AM","tmp":"--","desc":"Cloudy skies"}
            ],
            "dc": [
              {"d":"Today","dt":"12/31","tmin":"25 °C","tmax":"33 °C"},
              {"d":"Fri","dt":"01/01","desc":"Clear skies","tmin":"24 °C","tmax":"32 °C"}
            ]
          }
        }
        """.trimIndent()
    )

    @Test
    fun convertsManilaMidnightAndNewYear() {
        val result = response.toWeather(
            listOf(SourceFeature.FORECAST, SourceFeature.CURRENT),
            Instant.parse("2026-12-31T15:45:00Z").toEpochMilli()
        )
        result.failedFeatures?.isEmpty() shouldBe true
        result.current?.temperature?.feelsLike?.inCelsius shouldBe 31.0
        result.current?.weatherCode shouldBe WeatherCode.PARTLY_CLOUDY
        result.hourlyForecast?.get(1)?.date shouldBe Date.from(Instant.parse("2026-12-31T16:00:00Z"))
        result.hourlyForecast?.get(1)?.precipitation?.total?.inMillimeters shouldBe 0.42
        result.hourlyForecast?.get(2)?.temperature shouldBe null
        result.dailyForecast?.get(1)?.date shouldBe Date.from(Instant.parse("2026-12-31T16:00:00Z"))
        result.dailyForecast?.get(1)?.night?.temperature?.temperature?.inCelsius shouldBe 24.0
    }

    @Test
    fun marksStaleNowcastWithoutDroppingForecast() {
        val result = response.toWeather(
            listOf(SourceFeature.CURRENT, SourceFeature.FORECAST),
            Instant.parse("2026-12-31T19:00:00Z").toEpochMilli()
        )
        result.current shouldBe null
        (result.failedFeatures?.get(SourceFeature.CURRENT) is OutdatedServerDataException) shouldBe true
        result.dailyForecast?.size shouldBe 2
    }

    @Test
    fun rejectsDefaultManilaResponseForOtherCities() {
        val cebu = PanahonCoordinates("10.3158", "123.8855")
        cebu.matches(10.3157654, 123.8854678) shouldBe true
        PanahonCoordinates("14.5895", "120.9790").matches(10.3157654, 123.8854678) shouldBe false
        PanahonCoordinates().matches(10.3157654, 123.8854678) shouldBe false
    }

    @Test
    fun currentOnlyOmitsForecast() {
        val result = response.toWeather(
            listOf(SourceFeature.CURRENT),
            Instant.parse("2026-12-31T15:20:00Z").toEpochMilli()
        )
        result.current?.temperature?.temperature?.inCelsius shouldBe 28.0
        result.dailyForecast shouldBe null
        result.hourlyForecast shouldBe null
    }
}
