package io.homeassistant.companion.android.frontend.upstream

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import io.homeassistant.companion.android.BuildConfig
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.database.server.ServerConnectionInfo
import io.homeassistant.companion.android.util.sensitive
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

/**
 * Keeps the Home Assistant frontend on a fixed origin that exists only inside the app ([VirtualOrigin]),
 * while the server URL behind it (internal or external) changes with the network.
 *
 * Because the page origin never changes, switching between the internal and the external URL does not
 * reload the frontend: [FrontendUpstreamHttp] sends requests to the server URL in use, and the page script
 * ([FrontendUpstreamChannel]) moves the page's Home Assistant WebSocket to a new server URL before it lets
 * go of the old one.
 */
@Suppress("TooManyFunctions") // the one place that maps between both origins of each server
@Singleton
class FrontendUpstream @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serverManager: ServerManager,
) {
    private val bases = ConcurrentHashMap<Int, HttpUrl>()
    private val connections = ConcurrentHashMap<Int, ServerConnectionInfo>()

    /** Pages (main frame and frames) currently open on each virtual origin, to tell them about a switch. Main thread only. */
    private val pages = HashMap<Int, MutableSet<JavaScriptReplyProxy>>()

    private val _moves = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val _realUrlFallbacks = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Servers whose frontend needs the WebView's own loads (HTTP authentication), see [useRealUrl]. */
    private val realUrlServers = ConcurrentHashMap.newKeySet<Int>()

    /** Ids of servers whose open page just moved its WebSocket to a new server URL. */
    val moves: SharedFlow<Int> = _moves.asSharedFlow()

    /** Ids of servers that must now load their real URL instead, see [useRealUrl]. */
    val realUrlFallbacks: SharedFlow<Int> = _realUrlFallbacks.asSharedFlow()

    /** Whether the WebView supports what the virtual origin needs; without it the real URL is loaded. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /** Whether the frontend of [serverId] runs on the app-internal origin. */
    fun usesVirtualOrigin(serverId: Int): Boolean = isSupported && serverId !in realUrlServers

    /**
     * Makes [serverId] load its real URL from now on: its server (or a proxy before it) asks for HTTP
     * authentication or sends the frontend to another site, which only the WebView's own loads handle.
     */
    internal fun useRealUrl(serverId: Int) {
        if (!realUrlServers.add(serverId)) return
        Timber.i("Server $serverId needs the WebView's own loads, loading its real URL")
        _realUrlFallbacks.tryEmit(serverId)
    }

    /**
     * Makes [realUrl]'s origin the server URL in use for [serverId] and returns the same URL on the virtual
     * origin. An open page keeps its WebSocket on the previous server URL until [announceSwitch].
     */
    @MainThread
    fun useServerUrl(serverId: Int, realUrl: HttpUrl): HttpUrl {
        val base = realUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
        if (bases.put(serverId, base) != base) Timber.i("Server $serverId now uses ${sensitive(base.host)}")
        return VirtualOrigin.of(realUrl, serverId)
    }

    /** Tells the open pages of [serverId] to move their WebSocket to the server URL set by [useServerUrl]. */
    // Reply proxies only exist where FrontendUpstreamChannel.attach found WEB_MESSAGE_LISTENER
    @SuppressLint("RequiresFeature")
    @MainThread
    fun announceSwitch(serverId: Int) {
        val base = bases[serverId] ?: run {
            Timber.w("No server URL in use for server $serverId, nothing to announce")
            return
        }
        val message = FrontendUpstreamChannel.switchMessage(VirtualOrigin.webSocketBase(base))
        val open = pages[serverId]?.toList().orEmpty()
        Timber.i("Server $serverId switched to ${sensitive(base.host)}, telling ${open.size} open pages")
        open.forEach { it.postMessage(message) }
        debugToast("Test: switch sent to ${open.size} pages")
    }

    /** The server URL in use behind the virtual [host], or null for any other host. */
    fun baseFor(host: String?): HttpUrl? = VirtualOrigin.serverIdOf(host)?.let(bases::get)

    /** The real URL for [url] when it is on a virtual origin, otherwise [url] itself. */
    fun toRealUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        val base = baseFor(parsed?.host)
        return if (parsed == null || base == null) {
            url
        } else {
            parsed.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build().toString()
        }
    }

    /** [url] with a virtual host replaced by the host of the server URL in use, keeping scheme and port, or null for other URLs. */
    fun toRealHost(url: String): String? = url.toHttpUrlOrNull()?.let { parsed ->
        baseFor(parsed.host)?.let { base -> parsed.newBuilder().host(base.host).build().toString() }
    }

    /** Remembers the URLs of [serverId], to recognize links to the server's own addresses. */
    fun rememberConnection(serverId: Int, connection: ServerConnectionInfo) {
        connections[serverId] = connection
    }

    /**
     * The URL on the virtual origin of [serverId] for [url] when [url] points to one of the server's own
     * URLs (internal, external or cloud), otherwise null.
     */
    fun toVirtualUrl(serverId: Int, url: String): String? = url.toHttpUrlOrNull()
        ?.takeIf { connections[serverId]?.isKnownUrl(url) == true }
        ?.let { VirtualOrigin.of(it, serverId).toString() }

    /** Registers a page that just started on a virtual origin and tells it where its WebSockets go. */
    // Reply proxies only exist where FrontendUpstreamChannel.attach found WEB_MESSAGE_LISTENER
    @SuppressLint("RequiresFeature")
    @MainThread
    internal suspend fun onPageHello(host: String, replyProxy: JavaScriptReplyProxy, binary: Boolean) {
        val serverId = VirtualOrigin.serverIdOf(host) ?: return
        val connection = serverManager.getServer(serverId)?.connection
        // Nothing below suspends: no switch can come between the page's base and its registration
        val base = bases[serverId] ?: run {
            Timber.w("Page of server $serverId said hello before its server URL was set")
            return
        }
        pages.getOrPut(serverId) { Collections.newSetFromMap(WeakHashMap()) }.add(replyProxy)
        val message = FrontendUpstreamChannel.baseMessage(
            VirtualOrigin.webSocketBase(base),
            publicOrigin(connection, base),
            binary,
        )
        replyProxy.postMessage(message)
    }

    /** Called when a page finished moving its WebSocket after [announceSwitch]. */
    internal fun onPageMoved(host: String) {
        val serverId = VirtualOrigin.serverIdOf(host) ?: return
        Timber.i("Frontend of server $serverId moved to the new server URL without reloading")
        debugToast("Test: page moved")
        _moves.tryEmit(serverId)
    }

    /** A fresh access token for the page script, to log in its replacement WebSocket. */
    internal suspend fun accessToken(host: String): String? {
        val serverId = VirtualOrigin.serverIdOf(host) ?: return null
        return try {
            serverManager.authenticationRepository(serverId).retrieveAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to get an access token for the page of server $serverId")
            null
        }
    }

    /** The address used for links leaving the app: the remote URL the app uses, else [current]. */
    private fun publicOrigin(connection: ServerConnectionInfo?, current: HttpUrl): String {
        val remote = connection?.let { if (it.useCloud && it.cloudUrl != null) it.cloudUrl else it.externalUrl }
        val public = remote?.takeIf(String::isNotBlank)?.toHttpUrlOrNull() ?: current
        return public.toString().removeSuffix("/")
    }

    // TEMPORARY, test builds only: shows each step of a switch on the phone
    internal fun debugToast(text: String) {
        if (!BuildConfig.DEBUG) return
        Timber.i("Debug toast: $text")
        // makeText returns null in unit tests; the log line stays in logcat when Android drops a toast
        Toast.makeText(context, text, Toast.LENGTH_LONG)?.show()
    }
}
