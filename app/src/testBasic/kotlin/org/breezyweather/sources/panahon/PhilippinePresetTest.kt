package org.breezyweather.sources.panahon

import android.content.Context
import breezyweather.domain.location.model.Location
import breezyweather.domain.source.SourceFeature
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.collections.immutable.persistentListOf
import org.breezyweather.R
import org.breezyweather.common.extensions.currentLocale
import org.breezyweather.common.extensions.getStringByLocale
import org.breezyweather.common.source.WeatherSource
import org.breezyweather.domain.location.model.applyDefaultPreset
import org.breezyweather.sources.SourceManager
import org.breezyweather.sources.pagasa.PagasaService
import org.junit.jupiter.api.Test
import java.util.Locale

class PhilippinePresetTest {
    private val boracay = Location(latitude = 11.98, longitude = 121.92, countryCode = "PH")

    @Test
    fun automaticRankingSelectsPanahonAndPagasaRemainsSelectable() {
        val context = mockk<Context>()
        mockkStatic("org.breezyweather.common.extensions.LanguageExtensionsKt") {
            every { context.currentLocale } returns Locale.ENGLISH
            every {
                context.getStringByLocale(R.string.panahon_forecast_wind_credit, Locale.ENGLISH)
            } returns "Forecast wind: %s"
            every {
                context.getStringByLocale(R.string.panahon_current_wind_credit, Locale.ENGLISH)
            } returns "When using PANaHON forecasts, missing wind can come from %s"
            val pagasa = PagasaService(context, mockk())
            val panahon = PanahonService(context, mockk())
            val manager = mockk<SourceManager>()
            every { manager.getSupportedFeatureSources(any(), any(), null) } returns persistentListOf(pagasa, panahon)
            every { manager.getBestSourceForFeature(any(), any()) } answers { callOriginal() }

            for (feature in listOf(SourceFeature.FORECAST, SourceFeature.CURRENT)) {
                pagasa.isFeatureSupportedForLocation(boracay, feature) shouldBe true
                manager.getBestSourceForFeature(boracay, feature)?.id shouldBe "panahon"
                manager.getBestSourceForFeature(boracay.copy(countryCode = "ID"), feature) shouldBe null
            }
            panahon.getFeaturePriorityForLocation(boracay, SourceFeature.ALERT) shouldBe WeatherSource.PRIORITY_HIGH
        }
    }

    @Test
    fun gpsAutomaticSourcesFollowTheCountryAndRespectIndependentManualChoices() {
        val worldwide = mockk<WeatherSource>()
        every { worldwide.id } returns "openmeteo"
        val manager = mockk<SourceManager>()
        every { manager.getBestSourceForFeatureOrDefault(any(), any()) } returns null
        every { manager.getBestSourceForFeatureOrDefault(any(), SourceFeature.FORECAST) } returns worldwide
        every { manager.getBestSourceForFeature(any(), SourceFeature.CURRENT) } returns null
        every { manager.getDefaultSourceForFeature(any(), SourceFeature.CURRENT) } returns worldwide
        val gps = Location(isCurrentPosition = true).applyDefaultPreset(manager)
        gps.forecastSource shouldBe "openmeteo"
        gps.currentSource shouldBe "openmeteo"

        val ph = gps.copy(latitude = boracay.latitude, longitude = boracay.longitude, countryCode = "PH")
        ph.formattedId shouldBe Location.CURRENT_POSITION_ID
        ph.forecastSource shouldBe "panahon"
        ph.currentSource shouldBe "panahon"
        ph.copy(countryCode = "JP").forecastSource shouldBe "openmeteo"
        ph.copy(countryCode = "JP").currentSource shouldBe "openmeteo"
        ph.copy(automaticPhSource = null).forecastSource shouldBe "openmeteo"

        val override = ph.copy(currentSourcePreference = "pagasa")
        override.forecastSource shouldBe "panahon"
        override.currentSource shouldBe "pagasa"
        ph.copy(forecastSourcePreference = "openmeteo").forecastSource shouldBe "openmeteo"
        ph.copy(currentSourcePreference = null).currentSource shouldBe null
        ph.copy(currentSourcePreference = "").currentSource shouldBe ""
        val legacy = boracay.copy(forecastSourcePreference = "pagasa", currentSourcePreference = "pagasa")
        legacy.forecastSource shouldBe "pagasa"
        legacy.currentSource shouldBe "pagasa"
    }
}
