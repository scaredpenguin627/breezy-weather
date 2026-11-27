package org.breezyweather.sources.panahon

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.extensions.getStringByLocale
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class PanahonWindRequestTest {
    @Test
    fun currentOnlySkipsWindAndAFailedWindCallPreservesTheNativeForecastAndCurrent() {
        val location = Location(latitude = 11.98013, longitude = 121.91932, countryCode = "PH")
        val now = ZonedDateTime.now(ZoneId.of("Asia/Manila"))
        val hour = PanahonHour(time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)), tmp = "28 °C")
        val day = PanahonDay(dt = now.format(DateTimeFormatter.ofPattern("MM/dd", Locale.US)), tmax = "31 °C")
        val native = PanahonResponse(
            success = true,
            meta = PanahonCoordinates("11.9801", "121.9193"),
            data = PanahonDashboard(hour, listOf(hour), listOf(day))
        )
        val context = mockk<Context>()
        val builder = mockk<Retrofit.Builder>()
        val api = mockk<PanahonApi>()
        every { builder.baseUrl(any<String>()).build().create(PanahonApi::class.java) } returns api
        every { api.getDashboard(location.latitude, location.longitude) } returns Observable.just(native)
        every { api.getForecastWind(location.latitude, location.longitude) } returns Observable.error(IOException())
        mockkStatic("org.breezyweather.common.extensions.LanguageExtensionsKt") {
            every { context.currentLocale } returns Locale.ENGLISH
            every { context.getStringByLocale(any(), Locale.ENGLISH) } returns "%s"
            val service = PanahonService(context, builder)
            val current = service.requestWeather(context, location, listOf(SourceFeature.CURRENT)).blockingFirst()
            current.current?.temperature?.temperature?.inCelsius shouldBe 28.0
            verify(exactly = 0) { api.getForecastWind(any(), any()) }
            val features = listOf(SourceFeature.FORECAST, SourceFeature.CURRENT)
            val weather = service.requestWeather(context, location, features).blockingFirst()
            weather.failedFeatures?.isEmpty() shouldBe true
            weather.current shouldBe current.current
            weather.dailyForecast?.single()?.day?.temperature?.temperature?.inCelsius shouldBe 31.0
            weather.hourlyForecast?.single()?.wind shouldBe null
            verify(exactly = 1) { api.getForecastWind(location.latitude, location.longitude) }
        }
    }
}
