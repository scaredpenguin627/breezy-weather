package org.breezyweather.sources.panahon

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import breezyweather.domain.weather.reference.WeatherCode
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.breezyweather.common.extensions.currentLocale
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

class PanahonWebsiteRequestTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun isolatesOfficialForecastAndAlertFailuresAndKeepsSessionOffDisk() {
        val location = Location(latitude = 11.98013, longitude = 121.91932, countryCode = "PH")
        val context = mockk<Context>()
        every { context.cacheDir } returns directory
        val builder = mockk<Retrofit.Builder>()
        val api = mockk<PanahonApi>()
        val mapApi = mockk<PanahonMapApi>()
        val retrofit = mockk<Retrofit>()
        val mapClient = slot<OkHttpClient>()
        every { builder.baseUrl("https://panahon.gov.ph/").build() } returns retrofit
        every { retrofit.create(PanahonApi::class.java) } returns api
        every { retrofit.callFactory() } returns OkHttpClient.Builder().addInterceptor(HttpLoggingInterceptor()).build()
        every { retrofit.newBuilder().client(capture(mapClient)).build().create(PanahonMapApi::class.java) } returns
            mapApi
        val hour = Instant.now().truncatedTo(ChronoUnit.HOURS)
        val init = hour.minus(12, ChronoUnit.HOURS)
        val page = """
            <meta content="guest-test" name="csrf-token">
            <meta name="nwp-runs" content='{"ecmwf":"$init"}'>
        """.trimIndent()
        val response = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://panahon.gov.ph/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .addHeader("Set-Cookie", "guest=abc; Path=/; Secure; HttpOnly")
            .addHeader("Set-Cookie", "offpath=hidden; Path=/other; Secure")
            .build()
        every { mapApi.getMap() } answers { Observable.just(Response.success(page.toResponseBody(), response)) }
        val report = PanahonPointReport(
            true,
            "ecmwf",
            "mm",
            PanahonPointFields(
                icons = listOf(PanahonPoint(hour.mapLocalTime().replace('T', ' '), JsonPrimitive("icon-cloudy-skies")))
            )
        )
        every {
            mapApi.getPointForecast(location.latitude, location.longitude, init.toString(), "guest-test", "guest=abc")
        } returns
            Observable.just(report)
        every { mapApi.getTemperature(any(), any(), any(), any(), any(), any()) } returns
            Observable.error(IOException())
        every { api.getAlerts() } returns Observable.just(PanahonAlertResponse(true, PanahonAlertData(count = 0)))
        mockkStatic("org.breezyweather.common.extensions.LanguageExtensionsKt") {
            every { context.currentLocale } returns Locale.ENGLISH
            val service = PanahonService(context, builder)
            service.requestWeather(context, location, listOf(SourceFeature.ALERT)).blockingFirst().alertList shouldBe
                emptyList()
            verify(exactly = 0) { mapApi.getMap() }
            every { api.getAlerts() } returns Observable.error(IOException())
            val all = listOf(SourceFeature.FORECAST, SourceFeature.CURRENT, SourceFeature.ALERT)
            val weather = service.requestWeather(context, location, all).blockingFirst()
            weather.failedFeatures?.keys shouldBe setOf(SourceFeature.ALERT)
            weather.current?.weatherCode shouldBe WeatherCode.CLOUDY
            weather.current?.temperature shouldBe null
            weather.hourlyForecast?.single()?.weatherCode shouldBe WeatherCode.CLOUDY
            weather.alertList shouldBe null
            verify(exactly = 1) { mapApi.getTemperature(any(), any(), any(), any(), any(), any()) }
            mapClient.captured.interceptors.any { it is HttpLoggingInterceptor } shouldBe false
            mapClient.captured.cache shouldBe null
            mapClient.captured.followRedirects shouldBe false
            every { api.getAlerts() } returns Observable.just(PanahonAlertResponse(true, PanahonAlertData(count = 0)))
            every { mapApi.getMap() } returns Observable.error(IOException())
            val broken = service.requestWeather(context, location, all).blockingFirst()
            broken.failedFeatures?.keys shouldBe setOf(SourceFeature.FORECAST, SourceFeature.CURRENT)
            broken.alertList shouldBe emptyList()
        }
    }
}
