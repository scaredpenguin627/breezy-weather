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

class PanahonPointCacheTest {
    @TempDir
    lateinit var directory: File

    private val init = Instant.parse("2026-11-26T12:00:00Z")
    private val session = PanahonMapSession(init, "test-session", null)
    private val lat = 14.5995
    private val lon = 120.9842
    private val first = init.toEpochMilli()
    private val second = first + 3_600_000

    private fun point(latitude: Double, longitude: Double, run: Instant, value: Double) = PanahonMapValue(
        coordinates = listOf(longitude, latitude),
        values = listOf(JsonPrimitive(value)),
        run = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").format(run.atOffset(ZoneOffset.UTC))
    )

    @Test
    fun cachesOnlyValidatedValuesAndRetriesMissingTimesAfterRestart() {
        val calls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getPointValue(any(), any(), any(), any(), any(), any(), any()) } answers {
            val n = calls.incrementAndGet()
            val latitude = arg<Double>(1)
            val longitude = arg<Double>(2)
            val t = arg<String>(3)
            val run = Instant.parse(arg<String>(4))
            val value = if (t == Instant.ofEpochMilli(second).mapLocalTime() && n <= 2) {
                point(latitude, longitude, run.minusSeconds(3600), 75.0)
            } else {
                point(latitude, longitude, run, 0.0)
            }
            Observable.just(value)
        }
        val cache = PanahonPointCache(directory)
        cache.get(api, session, "temp", lat, lon, listOf(first, first, second)).blockingFirst().keys shouldBe
            setOf(first)
        calls.get() shouldBe 2
        val restarted = PanahonPointCache(directory)
        restarted.get(api, session, "temp", lat, lon, listOf(first, second)).blockingFirst() shouldBe
            mapOf(first to 0.0, second to 0.0)
        calls.get() shouldBe 3
        restarted.get(api, session, "temp", lat + 0.00001, lon, listOf(first)).blockingFirst()
        restarted.get(api, PanahonMapSession(init.plusSeconds(3600), "other", null), "temp", lat, lon, listOf(first))
            .blockingFirst()
        calls.get() shouldBe 5
        directory.walkTopDown().filter { it.isFile }.forEach { it.readText() shouldBe "0.0" }
    }

    @Test
    fun keepsEachLayerInItsOwnCacheDirectory() {
        val api = mockk<PanahonMapApi>()
        every { api.getPointValue(any(), any(), any(), any(), any(), any(), any()) } answers {
            Observable.just(
                point(arg(1), arg(2), Instant.parse(arg<String>(4)), if (arg<String>(0) == "rh") 67.9 else 28.4)
            )
        }
        val cache = PanahonPointCache(directory)
        cache.get(api, session, "temp", lat, lon, listOf(first)).blockingFirst()[first] shouldBe 28.4
        cache.get(api, session, "rh", lat, lon, listOf(first)).blockingFirst()[first] shouldBe 67.9
        File(directory, "temp").walkTopDown().count { it.isFile } shouldBe 1
        File(directory, "rh").walkTopDown().count { it.isFile } shouldBe 1
    }

    @Test
    fun overlappingSubscribersShareTheSameValidTimeRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getPointValue(any(), any(), any(), any(), any(), any(), any()) } returns Observable.fromCallable {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            point(lat, lon, init, 29.1)
        }
        val cache = PanahonPointCache(directory)
        val temperatures = cache.get(api, session, "temp", lat, lon, listOf(first))
        val one = temperatures.test()
        check(started.await(5, TimeUnit.SECONDS))
        val two = temperatures.test()
        release.countDown()
        one.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        two.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors()
        calls.get() shouldBe 1
        two.values().single()[first] shouldBe 29.1
    }

    @Test
    fun aFailedBatchStopsEarlyAndQueuedTimesUseTheNewSessionOnRetry() {
        val badCalls = AtomicInteger()
        val api = mockk<PanahonMapApi>()
        every { api.getPointValue(any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<String>(5) == session.token) {
                badCalls.incrementAndGet()
                Observable.error(IOException())
            } else {
                Observable.just(point(lat, lon, init, 28.5))
            }
        }
        val cache = PanahonPointCache(directory)
        val times = (0 until 100).map { first + it * 3_600_000L }
        cache.get(api, session, "temp", lat, lon, times).blockingFirst().isEmpty() shouldBe true
        (badCalls.get() in 4..7) shouldBe true
        val refreshed = PanahonMapSession(init, "new-session", null)
        cache.get(api, refreshed, "temp", lat, lon, times).blockingFirst().keys shouldBe times.toSet()
    }
}
