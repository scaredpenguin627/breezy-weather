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

package org.breezyweather.common.extensions

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import org.breezyweather.R
import java.util.Locale

/** Resolve provider wording at display time so cached conditions follow the app language. */
fun String?.localizedWeatherText(context: Context): String? {
    if (this == null) return null
    val resource = conditionResource(this) ?: return this
    // A widget or worker may start before AppCompat has restored its locales in this process.
    val locale = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
        AppCompatDelegate.getApplicationLocales().isEmpty
    ) {
        LocaleManagerCompat.getApplicationLocales(context)[0] ?: context.currentLocale
    } else {
        context.currentLocale
    }
    return if (locale.isChinese) context.getStringByLocale(resource, locale) else this
}

@StringRes
internal fun conditionResource(description: String): Int? = when (description.trim().lowercase(Locale.ROOT)) {
    "clear sky", "clear skies", "clear", "sunny" -> R.string.common_weather_text_clear_sky
    "mostly clear skies", "mostly clear" -> R.string.common_weather_text_mostly_clear
    "partly cloudy skies", "partly cloudy" -> R.string.common_weather_text_partly_cloudy
    "mostly cloudy skies", "mostly cloudy" -> R.string.common_weather_text_mostly_cloudy
    "cloudy skies", "cloudy" -> R.string.common_weather_text_cloudy
    "overcast skies", "overcast" -> R.string.common_weather_text_overcast
    "light rain", "light rains" -> R.string.common_weather_text_rain_light
    "moderate rain", "moderate rains" -> R.string.common_weather_text_rain_moderate
    "heavy rain", "heavy rains" -> R.string.common_weather_text_rain_heavy
    "rain", "rains" -> R.string.common_weather_text_rain
    "rain showers", "rainshowers" -> R.string.common_weather_text_rain_showers
    "drizzle" -> R.string.common_weather_text_drizzle
    "thunderstorm", "thunderstorms" -> R.string.weather_kind_thunderstorm
    "fog" -> R.string.common_weather_text_fog
    "haze" -> R.string.weather_kind_haze
    "partly cloudy skies to at times cloudy with rainshowers and thunderstorms" ->
        R.string.panahon_condition_partly_cloudy_with_showers_and_thunderstorms
    else -> null
}
