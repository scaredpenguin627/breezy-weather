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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.time.Instant

// Deliberately not a data class: session values should never appear in a diagnostic string.
internal class PanahonMapSession(val initialization: Instant, val token: String, val cookie: String?)

private val metaTag = Regex("""<meta\s+[^>]*>""", RegexOption.IGNORE_CASE)
private val attribute = Regex("""\b(name|content)\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)

internal fun Response<ResponseBody>.toPanahonMapSession(): PanahonMapSession {
    if (!isSuccessful) {
        errorBody()?.close()
        throw IOException("PANaHON map unavailable")
    }
    val html = body()?.use { it.string() } ?: throw IOException("Empty PANaHON map")
    val metadata = metaTag.findAll(html).mapNotNull { tag ->
        val attributes = attribute.findAll(tag.value).associate {
            it.groupValues[1].lowercase() to it.groupValues[3]
        }
        val name = attributes["name"] ?: return@mapNotNull null
        attributes["content"]?.let { name to it.replace("&quot;", "\"").replace("&amp;", "&") }
    }.toMap()
    val token = metadata["csrf-token"]?.takeIf { it.isNotBlank() } ?: throw IOException("Missing map session")
    val runs = metadata["nwp-runs"] ?: throw IOException("Missing map forecast runs")
    val initialization = Json.parseToJsonElement(runs).jsonObject["ecmwf"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing ECMWF initialization")
    val mapUrl = raw().request.url
    val forecastUrl = mapUrl.newBuilder().encodedPath("/api/v1/tiles/point/forecast").build()
    val cookies = Cookie.parseAll(mapUrl, headers())
        .filter { it.matches(forecastUrl) && it.expiresAt > System.currentTimeMillis() }
        .joinToString("; ") { "${it.name}=${it.value}" }
    return PanahonMapSession(Instant.parse(initialization), token, cookies.ifEmpty { null })
}
