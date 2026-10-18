package io.homeassistant.companion.android.frontend.upstream

import okhttp3.HttpUrl

/**
 * The app-internal origin the frontend of each server is served from: `https://server-<id>.homeassistant.internal`.
 *
 * `.internal` is reserved for private use (ICANN, 2024), so the name never resolves on the internet.
 */
internal object VirtualOrigin {
    const val SCHEME = "https"
    private const val DOMAIN = "homeassistant.internal"
    private const val HOST_PREFIX = "server-"

    /** Pattern of all virtual origins, for WebView origin rules. */
    const val ORIGIN_RULE = "$SCHEME://*.$DOMAIN"

    /** The virtual host of [serverId]. */
    fun hostFor(serverId: Int): String = "$HOST_PREFIX$serverId.$DOMAIN"

    /** The server id of a virtual [host], or null for any other host. */
    fun serverIdOf(host: String?): Int? = host
        ?.removeSuffix(".$DOMAIN")
        ?.removePrefix(HOST_PREFIX)
        ?.toIntOrNull()
        ?.takeIf { it >= 0 && hostFor(it) == host }

    /** [url] with its origin replaced by the virtual origin of [serverId]. */
    fun of(url: HttpUrl, serverId: Int): HttpUrl =
        url.newBuilder().scheme(SCHEME).host(hostFor(serverId)).port(HttpUrl.defaultPort(SCHEME)).build()

    /** The `ws`/`wss` origin matching the server [base] URL, without trailing slash. */
    fun webSocketBase(base: HttpUrl): String = base.toString().removeSuffix("/").replaceFirst("http", "ws")
}
