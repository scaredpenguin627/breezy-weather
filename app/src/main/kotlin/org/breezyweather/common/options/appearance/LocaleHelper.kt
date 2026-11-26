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

package org.breezyweather.common.options.appearance

import android.content.Context
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.breezyweather.R
import org.breezyweather.common.extensions.isTraditionalChinese
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

object LocaleHelper {

    fun getLangs(context: Context): ImmutableList<Language> {
        val langs = mutableListOf(Language("", context.getString(R.string.settings_follow_system)))
        context.resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    val tag = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    val locale = Locale.forLanguageTag(tag)
                    val displayName = when {
                        locale.isTraditionalChinese -> "繁體中文"
                        locale.language == "zh" -> "简体中文"
                        else -> locale.getDisplayName(locale)
                    }
                    langs.add(Language(tag, displayName))
                }
            }
        }
        return langs.toImmutableList()
    }

    // Map app-language overrides from versions which offered additional regional variants.
    fun supportedTag(locale: Locale?): String = when (locale?.language) {
        "en" -> "en"
        "zh" -> if (locale.isTraditionalChinese) "zh-TW" else "zh-CN"
        else -> ""
    }

    data class Language(
        val langTag: String,
        val displayName: String,
    )
}
