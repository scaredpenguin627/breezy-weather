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

package org.breezyweather

import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Base
import breezyweather.domain.weather.model.Weather
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Date

class LocationTest {

    @Test
    fun `current position movement is visible with the same cached weather and location id`() {
        val original = Location(
            latitude = 14.294,
            longitude = 121.0337,
            isCurrentPosition = true,
            weather = Weather(base = Base(refreshTime = Date(1000)))
        )
        val moved = original.copy(latitude = 14.293)

        moved.formattedId shouldBe original.formattedId
        (moved == original) shouldBe false
        original.copy() shouldBe original
        original.copy().hashCode() shouldBe original.hashCode()
    }
}
