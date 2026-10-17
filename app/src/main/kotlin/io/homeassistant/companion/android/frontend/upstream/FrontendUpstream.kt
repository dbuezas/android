package io.homeassistant.companion.android.frontend.upstream

import androidx.annotation.MainThread
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewFeature
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
@Singleton
class FrontendUpstream @Inject constructor(private val serverManager: ServerManager) {
    private val bases = ConcurrentHashMap<Int, HttpUrl>()
    private val connections = ConcurrentHashMap<Int, ServerConnectionInfo>()

    /** Pages (main frame and frames) currently open on each virtual origin, to tell them about a switch. */
    private val pages = ConcurrentHashMap<Int, MutableSet<JavaScriptReplyProxy>>()

    private val _moves = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Ids of servers whose open page just moved its WebSocket to a new server URL. */
    val moves: SharedFlow<Int> = _moves.asSharedFlow()

    /** Whether the WebView supports what the virtual origin needs; without it the real URL is loaded. */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

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
    @MainThread
    fun announceSwitch(serverId: Int) {
        val base = bases[serverId] ?: return
        val message = FrontendUpstreamChannel.switchMessage(VirtualOrigin.webSocketBase(base))
        val open = pages[serverId]?.let { synchronized(it) { it.toList() } }.orEmpty()
        Timber.i("Server $serverId switched to ${sensitive(base.host)}, telling ${open.size} open pages")
        open.forEach { it.postMessage(message) }
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
    @MainThread
    internal suspend fun onPageHello(host: String, replyProxy: JavaScriptReplyProxy, binary: Boolean) {
        val serverId = VirtualOrigin.serverIdOf(host) ?: return
        pages.getOrPut(serverId) { Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap())) }
            .add(replyProxy)
        val base = bases[serverId] ?: return
        val message = FrontendUpstreamChannel.baseMessage(
            VirtualOrigin.webSocketBase(base),
            publicOrigin(serverId, base),
            binary,
        )
        replyProxy.postMessage(message)
    }

    /** Called when a page finished moving its WebSocket after [announceSwitch]. */
    internal fun onPageMoved(host: String) {
        val serverId = VirtualOrigin.serverIdOf(host) ?: return
        Timber.i("Frontend of server $serverId moved to the new server URL without reloading")
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
            Timber.w(e, "Unable to get an access token for the page")
            null
        }
    }

    /** The address used for links leaving the app: the external URL when there is one. */
    private suspend fun publicOrigin(serverId: Int, current: HttpUrl): String {
        val connection = serverManager.getServer(serverId)?.connection
        val public = listOfNotNull(connection?.externalUrl, connection?.cloudUrl)
            .firstNotNullOfOrNull { it.takeIf(String::isNotBlank)?.toHttpUrlOrNull() } ?: current
        return public.toString().removeSuffix("/")
    }
}
