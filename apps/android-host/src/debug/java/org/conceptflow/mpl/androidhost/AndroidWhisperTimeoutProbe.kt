// SPDX-License-Identifier: MIT OR Apache-2.0
package org.conceptflow.mpl.androidhost

import android.content.Context
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.conceptflow.mpl.host.speech.AndroidWhisperCppEngine

/** Content-free physical verification that native CPU decoding respects its monotonic deadline. */
object AndroidWhisperTimeoutProbe {
    private val running = AtomicBoolean(false)
    @Volatile private var status = "not_run"

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        status = "running"
        val applicationContext = context.applicationContext
        Thread(
            {
                val engine = AndroidWhisperCppEngine(applicationContext)
                val samples = FloatArray(PROBE_SAMPLES) { index ->
                    if ((index / 40) % 2 == 0) 0.08f else -0.08f
                }
                val started = System.nanoTime()
                status = runCatching {
                    engine.prewarm()
                    val result = engine.transcribe(samples, PROBE_TIMEOUT_MILLIS)
                    val elapsedMillis = (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000.0
                    val outcome = if (result.timedOut) "timed_out" else "completed"
                    val verdict = if (elapsedMillis <= MAXIMUM_WALL_MILLIS) "passed" else "failed"
                    "%s outcome=%s elapsed_ms=%.1f".format(Locale.ROOT, verdict, outcome, elapsedMillis)
                }.getOrElse { error ->
                    "failed error=${error.javaClass.simpleName.take(48)}"
                }
                samples.fill(0f)
                runCatching { engine.close() }
                running.set(false)
            },
            "mpl-whisper-timeout-probe",
        ).apply { isDaemon = true }.start()
    }

    fun status(): String = status

    private const val PROBE_SAMPLES = 160_000
    private const val PROBE_TIMEOUT_MILLIS = 15_000L
    private const val MAXIMUM_WALL_MILLIS = 20_000.0
}
