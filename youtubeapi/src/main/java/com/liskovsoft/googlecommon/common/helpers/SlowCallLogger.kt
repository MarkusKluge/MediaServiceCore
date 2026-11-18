package com.liskovsoft.googlecommon.common.helpers

import android.os.SystemClock
import com.liskovsoft.sharedutils.mylogger.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * LOCAL (diagnostics): logs the phases of every API call that takes longer than [SLOW_CALL_MS]
 * (dns, tcp connect, tls, waiting for the response) and of every failed call, under the tag "SlowCall".
 * Example: a video start once spent 10 s silently inside the player request.
 */
internal class SlowCallLogger : EventListener() {
    companion object {
        private const val TAG = "SlowCall"
        private const val SLOW_CALL_MS = 1_500L

        @JvmField
        val FACTORY = EventListener.Factory { SlowCallLogger() }
    }

    private var callStart = 0L
    private var dnsStart = 0L
    private var dnsMs = -1L
    private var connectStart = 0L
    private var connectMs = -1L
    private var tlsStart = 0L
    private var tlsMs = -1L
    private var acquiredAt = 0L
    private var headersMs = -1L
    private var reused = true
    private var attempts = 0

    private fun now() = SystemClock.elapsedRealtime()

    override fun callStart(call: Call) {
        callStart = now()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = now() - dnsStart
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = now()
        reused = false
        attempts++
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = now()
    }

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        tlsMs = now() - tlsStart
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connectMs = now() - connectStart
    }

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        Log.w(TAG, "connect to %s failed after %d ms: %s", inetSocketAddress, now() - connectStart, ioe.message)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        acquiredAt = now()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        headersMs = now() - acquiredAt
    }

    override fun callEnd(call: Call) {
        val total = now() - callStart

        if (total >= SLOW_CALL_MS) {
            log("slow", call, total, null)
        }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log("failed", call, now() - callStart, ioe)
    }

    private fun log(kind: String, call: Call, total: Long, error: IOException?) {
        val url = call.request().url()
        Log.w(TAG, "%s %s%s %d ms: dns=%d connect=%d tls=%d response=%d connection=%s%s",
            kind, url.host(), url.encodedPath(), total, dnsMs, connectMs, tlsMs, headersMs,
            if (reused) "reused" else "new($attempts)", if (error != null) " error=${error.message}" else "")
    }
}
