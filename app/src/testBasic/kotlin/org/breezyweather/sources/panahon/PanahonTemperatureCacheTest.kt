package org.breezyweather.sources.panahon

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.core.Observable
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PanahonTemperatureCacheTest {
    @TempDir
    lateinit var directory: File

    private val init = Instant.parse("2026-11-26T12:00:00Z")
    private val session = PanahonMapSession(init, "test-session", null)
    private val lat = 14.5995
    private val lon = 120.9842
    private val first = init.toEpochMilli()
    private val second = first + 3_600_000

    private fun point(latitude: Double, longitude: Double, run: Instant, celsius: Double) = PanahonMapValue(
        coordinates = listOf(longitude, latitude),
        values = listOf(JsonPrimitive(celsius)),
        run = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(run.atOffset(ZoneOffset.UTC))
    )

    @Test
    fun cachesOnlyValidatedTemperaturesAndRetriesMissingTimesAfterRestart() {
        val calls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getTemperature(any(), any(), any(), any(), any(), any()) } answers {
            val n = calls.incrementAndGet()
            val latitude = firstArg<Double>()
            val longitude = secondArg<Double>()
            val t = thirdArg<String>()
            val run = Instant.parse(arg<String>(3))
            val value = if (t == Instant.ofEpochMilli(second).mapLocalTime() && n <= 2) {
                point(latitude, longitude, run.minusSeconds(3600), 75.0)
            } else {
                point(latitude, longitude, run, 0.0)
            }
            Observable.just(value)
        }
        val cache = PanahonTemperatureCache(directory)
        cache.get(api, session, lat, lon, listOf(first, first, second)).blockingFirst().keys shouldBe setOf(first)
        calls.get() shouldBe 2
        val restarted = PanahonTemperatureCache(directory)
        val values = restarted.get(api, session, lat, lon, listOf(first, second)).blockingFirst()
        values.mapValues { it.value.inCelsius } shouldBe mapOf(first to 0.0, second to 0.0)
        calls.get() shouldBe 3
        restarted.get(api, session, lat + 0.00001, lon, listOf(first)).blockingFirst()
        restarted.get(api, PanahonMapSession(init.plusSeconds(3600), "other", null), lat, lon, listOf(first))
            .blockingFirst()
        calls.get() shouldBe 5
        directory.walkTopDown().filter { it.isFile }.forEach { it.readText() shouldBe "0.0" }
    }

    @Test
    fun overlappingSubscribersShareTheSameValidTimeRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getTemperature(any(), any(), any(), any(), any(), any()) } returns Observable.fromCallable {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            point(lat, lon, init, 29.1)
        }
        val cache = PanahonTemperatureCache(directory)
        val temperatures = cache.get(api, session, lat, lon, listOf(first))
        val one = temperatures.test()
        check(started.await(5, TimeUnit.SECONDS))
        val two = temperatures.test()
        release.countDown()
        one.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        two.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        calls.get() shouldBe 1
        two.values().single()[first]?.inCelsius shouldBe 29.1
    }

    @Test
    fun aFailedBatchStopsEarlyAndQueuedTimesUseTheNewSessionOnRetry() {
        val badCalls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getTemperature(any(), any(), any(), any(), any(), any()) } answers {
            if (arg<String>(4) == session.token) {
                badCalls.incrementAndGet()
                Observable.error(IOException())
            } else {
                Observable.just(point(lat, lon, init, 28.5))
            }
        }
        val cache = PanahonTemperatureCache(directory)
        val times = (0 until 100).map { first + it * 3_600_000L }
        cache.get(api, session, lat, lon, times).blockingFirst().isEmpty() shouldBe true
        (badCalls.get() in 4..7) shouldBe true
        val refreshed = PanahonMapSession(init, "new-session", null)
        cache.get(api, refreshed, lat, lon, times).blockingFirst().keys shouldBe times.toSet()
    }
}
