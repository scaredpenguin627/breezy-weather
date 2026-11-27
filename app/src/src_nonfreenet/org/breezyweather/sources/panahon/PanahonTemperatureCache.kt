package org.breezyweather.sources.panahon

import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.breezyweather.unit.temperature.Temperature
import org.breezyweather.unit.temperature.Temperature.Companion.celsius
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Each temperature tile contains one coordinate, model run and valid time. */
internal class PanahonTemperatureCache(private val directory: File) {
    private val inFlight = mutableMapOf<File, Observable<Temperature>>()
    private val activeRuns = mutableMapOf<File, Int>()

    fun get(
        api: PanahonMapApi,
        session: PanahonMapSession,
        latitude: Double,
        longitude: Double,
        times: Collection<Long>,
    ): Observable<Map<Long, Temperature>> = Observable.defer {
        if (times.isEmpty()) return@defer Observable.just(emptyMap())
        val run =
            File(directory, "ecmwf_${latitude.toBits()}_${longitude.toBits()}_${session.initialization.toEpochMilli()}")
        val requested = times.distinct().sorted()
        begin(run)
        val cached = mutableMapOf<Long, Temperature>()
        val missing = requested.filter { time ->
            val value = read(File(run, time.toString()))
            if (value != null) cached[time] = value
            value == null
        }
        val currentHour = System.currentTimeMillis() / HOUR * HOUR
        val failures = AtomicInteger()
        Observable.fromIterable(prioritize(missing, currentHour))
            .flatMap({ time ->
                Observable.defer request@{
                    if (failures.get() >= PARALLELISM) return@request Observable.empty()
                    val file = File(run, time.toString())
                    shared(file) {
                        api.getTemperature(
                            latitude,
                            longitude,
                            Instant.ofEpochMilli(time).mapLocalTime(),
                            session.initialization.toString(),
                            session.token,
                            session.cookie
                        ).flatMapMaybe { point ->
                            Maybe.fromCallable {
                                point.airTemperature(latitude, longitude, session.initialization)?.temperature
                            }
                        }
                    }.map { temperature ->
                        failures.set(0)
                        time to temperature
                    }.onErrorResumeNext { _: Throwable ->
                        failures.incrementAndGet()
                        Observable.empty()
                    }
                }
            }, false, PARALLELISM)
            .take(if (requested.size == 1) 8L else 35L, TimeUnit.SECONDS)
            .collect({ cached }) { values, (time, temperature) -> values[time] = temperature }
            .map<Map<Long, Temperature>> { it }
            .toObservable()
            .doFinally { end(run) }
    }.subscribeOn(Schedulers.io())

    @Synchronized
    private fun shared(file: File, fetch: () -> Observable<Temperature>): Observable<Temperature> {
        // A concurrent refresh may have filled this time since our initial disk scan.
        read(file)?.let { return Observable.just(it) }
        return inFlight.getOrPut(file) {
            Observable.defer(fetch)
                .subscribeOn(Schedulers.io())
                .timeout(8, TimeUnit.SECONDS)
                .doOnNext { write(file, it) }
                .doOnTerminate { synchronized(this) { inFlight.remove(file) } }
                .cache()
        }
    }

    private fun read(file: File): Temperature? {
        if (!file.isFile) return null
        return try {
            file.readText().toDoubleOrNull()?.takeIf(Double::isFinite)?.celsius?.toValidOrNull()
        } catch (_: IOException) {
            null
        }
    }

    private fun write(file: File, value: Temperature) {
        // Immutable per-time entries: readers see either no file or the complete number.
        try {
            file.parentFile?.mkdirs()
            val temporary = File.createTempFile("temperature-", ".tmp", file.parentFile)
            try {
                temporary.writeText(value.inCelsius.toString())
                temporary.renameTo(file)
            } finally {
                temporary.delete()
            }
        } catch (_: IOException) {
            // Storage pressure must not discard a usable network reading.
        }
    }

    @Synchronized
    private fun begin(run: File) {
        activeRuns[run] = (activeRuns[run] ?: 0) + 1
        run.mkdirs()
        run.setLastModified(System.currentTimeMillis())
        val runs = directory.listFiles { file -> file.isDirectory && file.name.startsWith("ecmwf_") }.orEmpty()
        runs.sortedByDescending { it.lastModified() }.drop(12)
            .filterNot { it in activeRuns || inFlight.keys.any { sample -> sample.parentFile == it } }
            .forEach { it.deleteRecursively() }
    }

    @Synchronized
    private fun end(run: File) {
        val remaining = activeRuns.getValue(run) - 1
        if (remaining == 0) activeRuns.remove(run) else activeRuns[run] = remaining
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val PARALLELISM = 4
        private var appCache: PanahonTemperatureCache? = null

        @Synchronized
        fun forDirectory(directory: File): PanahonTemperatureCache =
            appCache?.takeIf { it.directory == directory } ?: PanahonTemperatureCache(directory).also { appCache = it }

        private fun prioritize(times: List<Long>, currentHour: Long) = buildSet {
            if (currentHour in times) add(currentHour)
            // Manila day/night start at 06:00/18:00 local (22:00/10:00 UTC).
            val periods = times.groupBy { (it + 2 * HOUR) / (12 * HOUR) }
            periods.forEach { (index, samples) ->
                val start = index * 12 * HOUR - 2 * HOUR
                // First cover every available period on a slow cold refresh.
                val target = start + (if (index % 2 == 0L) 8 else 10) * HOUR
                add(samples.minBy { abs(it - target) })
            }
            addAll(times)
        }
    }
}
