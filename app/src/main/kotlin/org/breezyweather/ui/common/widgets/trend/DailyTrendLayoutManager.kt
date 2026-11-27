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

package org.breezyweather.ui.common.widgets.trend

import android.content.Context
import android.view.View
import org.breezyweather.R
import org.breezyweather.common.extensions.fontScaleToApply
import kotlin.math.roundToInt

internal class DailyTrendLayoutManager(context: Context) : TrendLayoutManager(context) {

    override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
        val baseWidth = child.resources.getDimensionPixelSize(R.dimen.trend_item_width)
        val minWidth = (baseWidth * child.context.fontScaleToApply).roundToInt()
        child.layoutParams.width = dailyColumnWidth(
            width - paddingLeft - paddingRight - widthUsed,
            itemCount,
            getPosition(child),
            minWidth
        )
        super.measureChildWithMargins(child, widthUsed, heightUsed)
    }
}

internal fun dailyColumnWidth(availableWidth: Int, dayCount: Int, position: Int, minWidth: Int): Int {
    if (dayCount <= 0 || availableWidth <= 0) return minWidth
    // Give leftover pixels to the first columns in adapter order, including in RTL.
    val extra = if (position in 0 until availableWidth % dayCount) 1 else 0
    return (availableWidth / dayCount + extra).coerceAtLeast(minWidth)
}
