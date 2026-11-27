package org.breezyweather.common.extensions

import io.kotest.matchers.shouldBe
import org.breezyweather.R
import org.junit.jupiter.api.Test

class WeatherTextTest {
    @Test
    fun recognizesCachedProviderDescriptions() {
        conditionResource("Clear skies") shouldBe R.string.common_weather_text_clear_sky
        conditionResource(" clear SKY ") shouldBe R.string.common_weather_text_clear_sky
        conditionResource("Partly cloudy skies") shouldBe R.string.common_weather_text_partly_cloudy
        conditionResource("Light rains") shouldBe R.string.common_weather_text_rain_light
    }

    @Test
    fun preservesTheMeaningOfDetailedForecasts() {
        conditionResource("Partly cloudy skies to at times cloudy with rainshowers and thunderstorms") shouldBe
            R.string.panahon_condition_partly_cloudy_with_showers_and_thunderstorms
        conditionResource("Clear skies, becoming foggy later") shouldBe null
    }
}
