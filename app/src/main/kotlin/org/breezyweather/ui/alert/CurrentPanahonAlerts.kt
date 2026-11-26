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

package org.breezyweather.ui.alert

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import breezyweather.domain.weather.model.Alert
import kotlinx.coroutines.delay

@Composable
internal fun currentPanahonAlerts(alerts: List<Alert>): List<Alert> {
    var now by remember(alerts) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(alerts) {
        val boundaries = alerts.flatMap { listOfNotNull(it.startDate?.time, it.endDate?.time) }.distinct().sorted()
        for (boundary in boundaries) {
            val remaining = boundary - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            now = System.currentTimeMillis()
        }
    }
    return remember(alerts, now) {
        alerts.filter { it.isActiveAt(now) }
            .sortedWith(compareByDescending<Alert> { it.severity.id }.thenByDescending { it.startDate?.time })
    }
}
