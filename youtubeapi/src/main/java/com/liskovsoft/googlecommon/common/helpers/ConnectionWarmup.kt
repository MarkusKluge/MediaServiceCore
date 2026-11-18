package com.liskovsoft.googlecommon.common.helpers

import android.os.SystemClock
import com.liskovsoft.sharedutils.mylogger.Log
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * LOCAL: keeps DNS and the TLS connections of the API client warm while the app is in the foreground.<br/>
 * Opening a video after a while of idling had to resolve and connect from scratch first (pooled connections
 * are evicted after 5 idle minutes). The warmup goes through the API client itself, so its connection pool
 * holds the connections that the next player/browse request reuses.
 */
object ConnectionWarmup {
    private const val TAG = "ConnectionWarmup"
    private val URLS = listOf(
        "https://www.youtube.com/generate_204", // player, next, browse, stats
        "https://youtubei.googleapis.com/generate_204",
        "https://i.ytimg.com/generate_204" // thumbnails, storyboards
    )
    /** Below the 5 minutes after which the pool evicts idle connections. */
    private const val KEEP_WARM_INTERVAL_MS = 4 * 60_000L
    /** Activity switches pause and resume quickly; don't warm up for each of them. */
    private const val MIN_INTERVAL_MS = 60_000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, TAG).apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    @Volatile
    private var lastRunMs = 0L

    /** App (activity) in the foreground: warm up now, then keep the connections alive. */
    @JvmStatic
    @Synchronized
    fun start() {
        if (task != null) {
            return
        }

        task = executor.scheduleWithFixedDelay({ warmUp() }, 0, KEEP_WARM_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /** App in the background: stop keeping the connections alive. */
    @JvmStatic
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
    }

    private fun warmUp() {
        val start = SystemClock.elapsedRealtime()

        if (lastRunMs > 0 && start - lastRunMs < MIN_INTERVAL_MS) {
            return
        }

        lastRunMs = start

        for (url in URLS) {
            val t0 = SystemClock.elapsedRealtime()

            try {
                RetrofitOkHttpHelper.client.newCall(Request.Builder().url(url).head().build()).execute().close()
                Log.d(TAG, "%s warm in %d ms", url, SystemClock.elapsedRealtime() - t0)
            } catch (e: Exception) {
                Log.w(TAG, "%s warmup failed after %d ms: %s", url, SystemClock.elapsedRealtime() - t0, e.message)
            }
        }
    }
}
