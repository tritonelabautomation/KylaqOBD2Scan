package com.example.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime memory watchdog: the missing half of [HeapGuard].
 *
 * [HeapGuard] caps what a single call MAY load. It cannot know whether the process is *already*
 * near its ceiling, so every cap was chosen by guesswork and the app still died at
 * `target footprint 536870912` with a **56-byte** allocation (2026-11-16, owner's OnePlus
 * CPH2793). This object supplies the number those caps were guessing at.
 *
 * Three inputs, one output - a [HeapPressure.Level] the RAM-owning managers react to:
 *
 *  1. A sampler: `Runtime.totalMemory() - freeMemory()` against `maxMemory()`, every
 *     [SAMPLE_INTERVAL_MS], on a background dispatcher. This is the direct measurement.
 *  2. [onTrimMemory] / [onLowMemory]: the SYSTEM telling us, which is more authoritative than any
 *     threshold - the device knows how much RAM is left across all apps.
 *  3. [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] and friends, mapped by
 *     [HeapPressure.levelForTrim].
 *
 * Whatever the level is, it is (a) published as [level] for anything that collects it,
 * (b) pushed to every registered shedder, and (c) appended to a small rotating
 * [PRESSURE_LOG_NAME]. That log is the piece the previous crash reports lacked: they said a
 * 56-byte allocation failed and nothing about what the heap was holding or how close to the
 * ceiling it had been for how long. Next time, the record will say.
 *
 * ## Why the records stay silent no longer, without ever growing unbounded
 *
 * The pressure log is a diagnostic, not the record of a drive, so it is BOUNDED - it rotates at
 * [PRESSURE_LOG_MAX_BYTES] and keeps one previous generation. Crash files under `crash_logs/` are
 * still never deleted (never-lose-logs), and the shedders never touch anything on disk: every
 * ring they shrink is rebuilt from a file that is already written and flushed.
 */
object MemoryGuard : ComponentCallbacks2 {

    private const val TAG = "MemoryGuard"

    /** Bytes per MiB, for the human-readable diagnostics. */
    private const val MB = 1024L * 1024L

    /** Rotating diagnostics file in `files/`. Deliberately NOT inside `crash_logs/`. */
    const val PRESSURE_LOG_NAME = "memory_pressure.log"

    /** Rotate at this size, keeping `memory_pressure.log.1`. Two files, ~128 KiB total. */
    private const val PRESSURE_LOG_MAX_BYTES = 64L * 1024L

    /** How often the sampler looks at the heap while the process is alive. */
    const val SAMPLE_INTERVAL_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registered by whoever owns a RAM ring. Called on the sampler/dispatcher thread.
     *
     * Held WEAKLY: this object lives for the whole process, and a shedder lambda captures the
     * manager that registered it. A strong registration would pin every manager ever created -
     * a memory watchdog that itself leaks is worse than none, and in the JVM tests (which build
     * dozens of managers) it would keep every test's rings alive.
     */
    private val shedders = CopyOnWriteArrayList<java.lang.ref.WeakReference<(HeapPressure.Level) -> Unit>>()

    private val _level = kotlinx.coroutines.flow.MutableStateFlow(HeapPressure.Level.NORMAL)

    /** Current pressure band, for UI that wants to warn before a crash makes it obvious. */
    val level: kotlinx.coroutines.flow.StateFlow<HeapPressure.Level> = _level

    @Volatile
    var lastUsedBytes: Long = 0L
        private set

    @Volatile
    var lastMaxBytes: Long = 0L
        private set

    @Volatile
    var lastTrimLevel: Int = 0
        private set

    /** Times a shed was forced. Non-zero means the guard is doing its job, not that we are fine. */
    @Volatile
    var shedCount: Int = 0
        private set

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var sampler: Job? = null

    @Volatile
    private var started = false

    /**
     * Idempotent. Call from `Application.onCreate`, before anything can allocate a ring.
     *
     * Never throws: a watchdog that crashes the app it watches is worse than no watchdog.
     */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        runCatching {
            val app = context.applicationContext
            logFile = File(app.filesDir, PRESSURE_LOG_NAME)
            app.registerComponentCallbacks(this)
        }.onFailure { android.util.Log.w(TAG, "could not register memory callbacks", it) }

        sampler = scope.launch {
            while (isActive) {
                runCatching { refresh(trigger = "sample") }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        // First measurement immediately, so `level` is never a placeholder at startup.
        runCatching { refresh(trigger = "start") }
    }

    /**
     * Registers a shedder and immediately tells it the current level, so a manager built while the
     * heap is already tight starts small instead of growing into the ceiling first.
     */
    fun addShedder(shedder: (HeapPressure.Level) -> Unit) {
        shedders.add(java.lang.ref.WeakReference(shedder))
        runCatching { shedder(currentLevel()) }
    }

    /** Live shedders, dropping the ones whose owner has been collected. */
    private fun liveShedders(): List<(HeapPressure.Level) -> Unit> {
        val out = ArrayList<(HeapPressure.Level) -> Unit>(shedders.size)
        val dead = ArrayList<java.lang.ref.WeakReference<(HeapPressure.Level) -> Unit>>()
        for (ref in shedders) {
            val shedder = ref.get()
            if (shedder == null) dead.add(ref) else out.add(shedder)
        }
        if (dead.isNotEmpty()) shedders.removeAll(dead)
        return out
    }

    private fun shedAll(level: HeapPressure.Level) {
        for (shedder in liveShedders()) {
            runCatching { shedder(level) }
                .onFailure { android.util.Log.w(TAG, "shedder failed", it) }
        }
    }

    /** Current band. Cheap - a StateFlow read, no Runtime call. */
    fun currentLevel(): HeapPressure.Level = _level.value

    /** Current band from the ring-size / throttle helpers' point of view. */
    fun ringSize(normal: Int, elevated: Int, critical: Int): Int =
        HeapPressure.ringSizeAt(currentLevel(), normal, elevated, critical)

    /** Whether a throttled publisher should emit now - see [HeapPressure.shouldPublish]. */
    fun shouldPublish(lastPublishMs: Long, nowMs: Long, normalMs: Long = 250L): Boolean =
        HeapPressure.shouldPublish(
            lastPublishMs,
            nowMs,
            HeapPressure.publishIntervalAt(currentLevel(), normalMs, normalMs * 4, normalMs * 8)
        )

    /**
     * Samples the heap now and acts on it. Returns the observed band.
     *
     * Called by the sampler every [SAMPLE_INTERVAL_MS] and by the system's trim callbacks, so a
     * sudden squeeze is handled immediately rather than up to 5 s later.
     */
    fun refresh(trigger: String = "manual"): HeapPressure.Level {
        val runtime = Runtime.getRuntime()
        val used = HeapPressure.usedBytes(runtime.totalMemory(), runtime.freeMemory())
        val max = runtime.maxMemory()
        lastUsedBytes = used
        lastMaxBytes = max
        val level = HeapPressure.levelOf(used, max)

        val previous = _level.value
        _level.value = level

        // Act on every change, and keep acting while CRITICAL: a ring re-grown by a live drive
        // must be pushed back down, not left at whatever size it reached before the squeeze.
        if (level != previous || level == HeapPressure.Level.CRITICAL) {
            shedCount++
            shedAll(level)
            record(level, used, max, trigger)
        }
        return level
    }

    /**
     * Test seam: push a band through the same shed path the system callbacks use, without needing
     * a real 512 MB squeeze. Production never calls this ([applyBand] is the entry point).
     */
    internal fun forceBandForTest(level: HeapPressure.Level) = applyBand(level, "test")

    // ── System callbacks ────────────────────────────────────────────────────────────────

    override fun onTrimMemory(level: Int) {
        lastTrimLevel = level
        if (level >= HeapPressure.TRIM_MEMORY_RUNNING_MODERATE) {
            // The system knows more than our threshold does: shed even if Runtime still looks
            // comfortable, because "comfortable" is measured inside a process about to be squeezed.
            val band = HeapPressure.levelForTrim(level)
            if (band != HeapPressure.Level.NORMAL) {
                applyBand(band, trigger = "onTrimMemory($level)")
            }
        }
        // Always re-measure: this callback is exactly when a real number is worth having.
        runCatching { refresh(trigger = "onTrimMemory($level)") }
    }

    override fun onLowMemory() {
        applyBand(HeapPressure.Level.CRITICAL, trigger = "onLowMemory")
        runCatching { refresh(trigger = "onLowMemory") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun applyBand(band: HeapPressure.Level, trigger: String) {
        if (band == HeapPressure.Level.NORMAL) return
        _level.value = band
        shedCount++
        shedAll(band)
        record(band, lastUsedBytes, lastMaxBytes, trigger)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────────────────

    /**
     * One line per event, bounded by rotation. A read-only accessor exists so a crash record or a
     * support screen can quote what led up to a death instead of guessing.
     */
    private fun record(level: HeapPressure.Level, used: Long, max: Long, trigger: String) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > PRESSURE_LOG_MAX_BYTES) {
                val previous = File(file.parentFile, "$PRESSURE_LOG_NAME.1")
                if (previous.exists()) previous.delete()
                file.renameTo(previous)
            }
            val fraction = (HeapPressure.usageFraction(used, max) * 100).toInt()
            file.appendText(
                "${RecordTime.stamp()} $trigger level=$level " +
                    "used=${used / (1024 * 1024)}MB max=${max / (1024 * 1024)}MB (${fraction}%) " +
                    "thread=${Thread.currentThread().name}\n"
            )
        }
    }

    /**
     * One-line heap summary for a crash record, e.g.
     * `used=498MB max=512MB (97%) level=CRITICAL trim=15 sheds=12`.
     *
     * Reads Runtime fresh rather than trusting the sampler's last reading: at the moment a crash
     * handler runs, the difference between "5 seconds ago" and "now" can be the whole story.
     * Wrapped so a failing Runtime cannot turn a crash record into a second crash.
     */
    fun describeState(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val used = HeapPressure.usedBytes(runtime.totalMemory(), runtime.freeMemory())
        val max = runtime.maxMemory()
        val pct = (HeapPressure.usageFraction(used, max) * 100).toInt()
        "used=${used / MB}MB max=${max / MB}MB (${pct}%) level=${_level.value} " +
            "trim=$lastTrimLevel sheds=$shedCount"
    }.getOrDefault("unavailable")

    /**
     * The tail of the pressure log, for a crash record or the About screen. Bounded read - the
     * file is capped at [PRESSURE_LOG_MAX_BYTES], but a read still never assumes that.
     */
    fun pressureLogTail(maxChars: Int = 4_000): String? {
        val file = logFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            val length = file.length()
            val from = (length - maxChars.toLong()).coerceAtLeast(0L)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((length - from).toInt().coerceAtLeast(0))
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** Test/hook seam: forget registrations and go back to NORMAL. */
    internal fun resetForTests() {
        shedders.clear()

        _level.value = HeapPressure.Level.NORMAL
        lastUsedBytes = 0L
        lastMaxBytes = 0L
        lastTrimLevel = 0
        shedCount = 0
        sampler?.cancel()
        sampler = null
        started = false
        logFile = null
    }
}
