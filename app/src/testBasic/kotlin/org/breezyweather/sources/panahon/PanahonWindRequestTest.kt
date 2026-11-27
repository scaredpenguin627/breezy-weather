package org.breezyweather.sources.panahon

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import io.reactivex.rxjava3.core.Observable
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.extensions.getStringByLocale
import org.junit.jupiter.api.Test
import retrofit2.Response
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
        val mapApi = mockk<PanahonMapApi>()
        val retrofit = mockk<Retrofit>()
        val mapClient = slot<OkHttpClient>()
        every { builder.baseUrl(any<String>()).build() } returns retrofit
        every { retrofit.create(PanahonApi::class.java) } returns api
        every { retrofit.callFactory() } returns OkHttpClient.Builder().addInterceptor(HttpLoggingInterceptor()).build()
        every {
            retrofit.newBuilder().baseUrl("https://panahon.gov.ph/").client(capture(mapClient)).build()
                .create(PanahonMapApi::class.java)
        } returns mapApi
        every { api.getDashboard(location.latitude, location.longitude) } returns Observable.just(native)
        val page = """
            <meta content="guest-test" name="csrf-token">
            <meta name="nwp-runs" content='{"ecmwf":"2026-11-26T12:00:00Z","atmo":"2026-11-26T00:00:00Z"}'>
        """.trimIndent()
        val response = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://panahon.gov.ph/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .addHeader("Set-Cookie", "guest=abc; Path=/; Secure; HttpOnly")
            .addHeader("Set-Cookie", "offpath=hidden; Path=/other; Secure")
            .build()
        every { mapApi.getMap() } returns Observable.just(Response.success(page.toResponseBody(), response))
        every {
            mapApi.getForecastWind(
                location.latitude,
                location.longitude,
                "2026-11-26T12:00:00Z",
                "guest-test",
                "guest=abc"
            )
        } returns Observable.error(IOException())
        mockkStatic("org.breezyweather.common.extensions.LanguageExtensionsKt") {
            every { context.currentLocale } returns Locale.ENGLISH
            every { context.getStringByLocale(any(), Locale.ENGLISH) } returns "%s"
            val service = PanahonService(context, builder)
            val current = service.requestWeather(context, location, listOf(SourceFeature.CURRENT)).blockingFirst()
            current.current?.temperature?.temperature?.inCelsius shouldBe 28.0
            verify(exactly = 0) { mapApi.getMap() }
            val features = listOf(SourceFeature.FORECAST, SourceFeature.CURRENT)
            val weather = service.requestWeather(context, location, features).blockingFirst()
            weather.failedFeatures?.isEmpty() shouldBe true
            weather.current shouldBe current.current
            weather.dailyForecast?.single()?.day?.temperature?.temperature?.inCelsius shouldBe 31.0
            weather.hourlyForecast?.single()?.wind shouldBe null
            verify(exactly = 1) { mapApi.getForecastWind(any(), any(), any(), any(), any()) }
            mapClient.captured.interceptors.any { it is HttpLoggingInterceptor } shouldBe false
            mapClient.captured.cache shouldBe null
            mapClient.captured.followRedirects shouldBe false
        }
    }
}
