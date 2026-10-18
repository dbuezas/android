package io.homeassistant.companion.android.frontend.upstream

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.Response
import timber.log.Timber

private const val STATUS_OK = 200
private const val STATUS_PARTIAL_CONTENT = 206
private const val STATUS_MOVED_PERMANENTLY = 301
private const val STATUS_FOUND = 302
private const val STATUS_SEE_OTHER = 303
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_PROXY_AUTHENTICATION_REQUIRED = 407
private const val STATUS_BAD_GATEWAY = 502
private val VALID_STATUSES = 100..599
private val PRINTABLE_ASCII = ' '..'~'
private val SAFE_METHODS = setOf("GET", "HEAD")
internal val REDIRECT_STATUSES = 300..399

/** Set on the 502 that answers a failed main-frame load, with the WebViewClient error code of the cause. */
internal const val UPSTREAM_ERROR_HEADER = "X-Frontend-Upstream-Error"

/** Response headers that describe the transfer, which OkHttp has already handled. */
private val DROPPED_RESPONSE_HEADERS = setOf("content-encoding", "content-length", "transfer-encoding", "connection")

/** Response headers the page never sees: transfer headers, and cookies (stored in [CookieManager] instead). */
private val HIDDEN_RESPONSE_HEADERS = DROPPED_RESPONSE_HEADERS + setOf("set-cookie", "set-cookie2")

// Answers of FrontendUpstreamHttp: what the WebView and the page script get back.

/**
 * The WebView applies the request's Range to an intercepted stream itself: it skips the range start and
 * takes the length from available(). A 206 already starts at the range start, so that skip is absorbed.
 */
private class RangedStream(input: InputStream, response: Response) : FilterInputStream(input) {
    private var absorbable = 0L
    private var position = 0L
    private val size: Long

    init {
        val range = response.header("Content-Range")
            ?.let { RANGE.matchEntire(it) }
            ?.takeIf { response.code == STATUS_PARTIAL_CONTENT }
        if (range != null) {
            absorbable = range.groupValues[1].toLong()
            size = range.groupValues[2].toLong() + 1
        } else {
            size = response.body.contentLength()
        }
    }

    override fun skip(n: Long): Long {
        val absorbed = minOf(n, absorbable)
        absorbable -= absorbed
        position += absorbed
        val skipped = if (n > absorbed) super.skip(n - absorbed) else 0
        position += skipped
        return absorbed + skipped
    }

    override fun read(): Int = super.read().also { if (it >= 0) position++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
        if (it >
            0
        ) {
            position += it
        }
    }

    override fun available(): Int = when {
        size < 0 -> super.available()
        size - position > Int.MAX_VALUE -> 0
        else -> (size - position).coerceAtLeast(0).toInt()
    }

    private companion object {
        val RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}

internal fun Response.redirectTarget(): HttpUrl? = header("Location")?.let(request.url::resolve)

/** A challenge for credentials the WebView asks the user for; Home Assistant's own 401s carry none. */
internal fun Response.needsWebViewAuthentication(): Boolean =
    code == STATUS_PROXY_AUTHENTICATION_REQUIRED || (code == STATUS_UNAUTHORIZED && header("WWW-Authenticate") != null)

private fun Response.pageHeaders(): List<Pair<String, String>> =
    headers.filter { it.first.lowercase() !in HIDDEN_RESPONSE_HEADERS }

internal fun Response.toAppMessage(id: Int) = FrontendUpstreamChannel.AppMessage(
    id = id,
    status = code,
    statusText = message,
    headers = pageHeaders().map { listOf(it.first, it.second) },
    body = body.byteString().base64(),
)

/** Only what the WebView accepts: it throws for 3xx, out-of-range codes and non-ASCII reasons. */
internal fun Response.toWebResourceResponse(): WebResourceResponse {
    if (code !in VALID_STATUSES || code in REDIRECT_STATUSES) {
        close()
        return badGateway()
    }
    val type = body.contentType()
    val headers = pageHeaders().groupBy({ it.first }, { it.second }).mapValues { it.value.joinToString(", ") }
    return WebResourceResponse(
        type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
        type?.charset()?.name(),
        code,
        message.takeIf { it.isNotBlank() && it.all { c -> c in PRINTABLE_ASCII } } ?: "OK",
        headers,
        RangedStream(body.byteStream(), this),
    )
}

internal fun plainResponse(status: Int, reason: String) =
    WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(reason.toByteArray()))

internal fun htmlResponse(html: String) =
    WebResourceResponse("text/html", "utf-8", STATUS_OK, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))

/** A page that makes the WebView navigate to [url], so a redirected document renders under its own URL. */
internal fun refreshTo(url: HttpUrl): WebResourceResponse {
    val escaped = buildString {
        url.toString().forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(it)
            }
        }
    }
    return htmlResponse("<!doctype html><meta http-equiv=\"refresh\" content=\"0;url=$escaped\">")
}

/** Whether following this redirect makes the request a GET without body, as browsers do. */
internal fun Response.turnsIntoGet(method: String): Boolean =
    code == STATUS_SEE_OTHER || (code in STATUS_MOVED_PERMANENTLY..STATUS_FOUND && method !in SAFE_METHODS)

internal fun badGateway() = plainResponse(STATUS_BAD_GATEWAY, "Bad Gateway")

/** The 502 that answers a failed load; for the main frame it carries the cause, see [UPSTREAM_ERROR_HEADER]. */
internal fun failedLoad(serverId: Int, isMainFrame: Boolean, e: Exception?): WebResourceResponse {
    Timber.w("Failed to load a frontend request for server $serverId: ${e?.javaClass?.simpleName}")
    val code = when (e) {
        is UnknownHostException -> WebViewClient.ERROR_HOST_LOOKUP
        is ConnectException, is NoRouteToHostException -> WebViewClient.ERROR_CONNECT
        is SocketTimeoutException -> WebViewClient.ERROR_TIMEOUT
        is SSLException -> WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
        else -> WebViewClient.ERROR_UNKNOWN
    }
    return badGateway().apply { if (isMainFrame) responseHeaders = mapOf(UPSTREAM_ERROR_HEADER to code.toString()) }
}
