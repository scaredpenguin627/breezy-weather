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

package org.breezyweather.sources.pagasa

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.breezyweather.sources.pagasa.json.PagasaCurrentResult
import org.junit.jupiter.api.Test

class PagasaCurrentResultTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesMixedPressureReadingsWithoutDroppingOtherStations() {
        val fields = """
            "datetime":"November 27, 2026, 10:20 am","temperature":"31 °C","humidity":"70 %",
            "wind_speed":"10.8 km/hr","wind_direction":"SSE","latitude":"14.087723","longitude":"121.062396"
        """.trimIndent()
        val response = """
            {
                "41031000":[{$fields,"pressure":1013.9},{$fields,"pressure":"--"},{$fields,"pressure":1011}],
                "175910000":[{$fields,"pressure":"995.2"},{$fields,"pressure":null},{$fields}]
            }
        """.trimIndent()
        val stations = json.decodeFromString<Map<String, List<PagasaCurrentResult>>>(response)

        stations.getValue("41031000").map { it.pressureHpa } shouldBe listOf(1013.9, null, 1011.0)
        val nearby = stations.getValue("175910000")
        nearby.map { it.pressureHpa } shouldBe listOf(995.2, null, null)
        nearby.last().temperature shouldBe "31 °C"
        nearby.last().windDirection shouldBe "SSE"
    }
}
