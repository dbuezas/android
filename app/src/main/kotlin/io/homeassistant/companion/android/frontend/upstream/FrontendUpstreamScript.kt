package io.homeassistant.companion.android.frontend.upstream

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

private const val LISTENER = "androidInternalHaUpstream"

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true // the page reads "type" on every message
}

/**
 * The page side of [FrontendUpstream], injected at document start into the virtual origins only.
 *
 * - WebSockets to the page's own origin are opened to the current server instead. On a server switch
 *   they are closed at once (the page sees an ordinary close and reconnects to the new server).
 * - `fetch` calls with a body (POST, PUT, ...) to the page's own origin go through the app, because
 *   the WebView's request interception cannot read request bodies. All other requests are intercepted
 *   by [FrontendUpstream.intercept].
 */
private val SCRIPT = """
(() => {
  if (window.__haUpstream) return;
  const channel = window.$LISTENER;
  if (!channel) return;
  const NativeWebSocket = window.WebSocket;
  const nativeFetch = window.fetch.bind(window);
  const sockets = new Set();
  const pending = new Map();
  let wsBase = null;
  let waiting = [];
  let nextId = 0;

  const toUpstream = (raw) => {
    const url = new URL(raw, location.href);
    if (url.host !== location.host) return url.href;
    const base = new URL(wsBase);
    url.protocol = base.protocol;
    url.host = base.host;
    return url.href;
  };

  class HaWebSocket extends EventTarget {
    constructor(url, protocols) {
      super();
      this._url = new URL(url, location.href).href;
      this._protocols = protocols;
      this._closed = false;
      this._ws = null;
      this._binaryType = 'blob';
      this.onopen = this.onmessage = this.onerror = this.onclose = null;
      sockets.add(this);
      if (wsBase) this._open(); else waiting.push(this);
    }
    _open() {
      if (this._closed) return;
      const target = toUpstream(this._url);
      this._ws = this._protocols === undefined ? new NativeWebSocket(target) : new NativeWebSocket(target, this._protocols);
      this._ws.binaryType = this._binaryType;
      for (const type of ['open', 'message', 'error', 'close']) this._ws.addEventListener(type, (e) => this._emit(type, e));
    }
    _emit(type, e) {
      if (this._closed) return;
      if (type === 'close') { this._closed = true; sockets.delete(this); }
      const event = type === 'message' ? new MessageEvent('message', { data: e.data, origin: e.origin })
        : type === 'close' ? new CloseEvent('close', { code: e.code, reason: e.reason, wasClean: e.wasClean })
        : new Event(type);
      const handler = this['on' + type];
      if (typeof handler === 'function') handler.call(this, event);
      this.dispatchEvent(event);
    }
    _drop(reason) {
      if (this._closed) return;
      try { this._ws && this._ws.close(); } catch (e) {}
      this._emit('close', { code: 1006, reason, wasClean: false });
    }
    get url() { return this._url; }
    get readyState() { return this._closed ? 3 : (this._ws ? this._ws.readyState : 0); }
    get protocol() { return this._ws ? this._ws.protocol : ''; }
    get extensions() { return this._ws ? this._ws.extensions : ''; }
    get bufferedAmount() { return this._ws ? this._ws.bufferedAmount : 0; }
    get binaryType() { return this._binaryType; }
    set binaryType(value) { this._binaryType = value; if (this._ws) this._ws.binaryType = value; }
    send(data) {
      if (!this._ws || this._ws.readyState !== 1) throw new DOMException('WebSocket is not open', 'InvalidStateError');
      this._ws.send(data);
    }
    close(code, reason) {
      if (this._ws) this._ws.close(code, reason); else this._drop(reason || '');
    }
  }
  for (const [name, value] of [['CONNECTING', 0], ['OPEN', 1], ['CLOSING', 2], ['CLOSED', 3]]) {
    HaWebSocket[name] = value;
    HaWebSocket.prototype[name] = value;
  }
  window.WebSocket = HaWebSocket;

  const toBase64 = (bytes) => {
    let text = '';
    for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(text);
  };
  const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

  window.fetch = async (input, init) => {
    const method = ((init && init.method) || (input instanceof Request ? input.method : 'GET')).toUpperCase();
    if (method === 'GET' || method === 'HEAD') return nativeFetch(input, init);
    const request = new Request(input, init);
    const url = new URL(request.url);
    if (url.host !== location.host) return nativeFetch(request);
    const body = new Uint8Array(await request.arrayBuffer());
    const id = ++nextId;
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      channel.postMessage(JSON.stringify({
        type: 'request', id, method, path: url.pathname + url.search,
        headers: [...request.headers.entries()], body: body.length ? toBase64(body) : null,
      }));
    });
  };

  channel.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'base') {
      wsBase = message.ws;
      const queued = waiting; waiting = [];
      queued.forEach((socket) => socket._open());
    } else if (message.type === 'switch') {
      wsBase = message.ws;
      [...sockets].forEach((socket) => socket._drop('Server address changed'));
    } else if (message.type === 'response') {
      const call = pending.get(message.id);
      if (!call) return;
      pending.delete(message.id);
      if (message.error) { call.reject(new TypeError(message.error)); return; }
      const noBody = [101, 204, 205, 304].includes(message.status);
      call.resolve(new Response(noBody ? null : fromBase64(message.body || ''), {
        status: message.status, statusText: message.statusText, headers: message.headers,
      }));
    }
  };
  window.__haUpstream = true;
  channel.postMessage(JSON.stringify({ type: 'hello' }));
})();
""".trimIndent()

/** Registers and removes the page side of [FrontendUpstream] on WebViews. */
internal object FrontendUpstreamScript {
    private val scripts = WeakHashMap<WebView, ScriptHandler>()

    @Serializable
    internal data class BridgeRequest(
        val type: String,
        val id: Int = 0,
        val method: String = "GET",
        val path: String = "/",
        val headers: List<List<String>> = emptyList(),
        val body: String? = null,
    )

    @Serializable
    internal data class BridgeResponse(
        val type: String = "response",
        val id: Int,
        val status: Int = 0,
        val statusText: String = "",
        val headers: List<List<String>> = emptyList(),
        val body: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class BaseMessage(val type: String, val ws: String)

    fun baseMessage(webSocketBase: String): String = json.encodeToString(BaseMessage("base", webSocketBase))

    fun switchMessage(webSocketBase: String): String = json.encodeToString(BaseMessage("switch", webSocketBase))

    /** Adds the script and its message channel to [webView], once. */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView, upstream: FrontendUpstream, scope: CoroutineScope) {
        if (!upstream.isSupported || scripts.containsKey(webView)) return
        val rules = setOf(upstream.originRule)
        val webViewRef = WeakReference(webView)
        WebViewCompat.addWebMessageListener(webView, LISTENER, rules) { _, message, sourceOrigin, _, replyProxy ->
            val host = sourceOrigin.host
            // The origin rules already limit this to the virtual origins; frames on them need the bridge too
            if (host == null || !upstream.isVirtualHost(host)) return@addWebMessageListener
            val call = runCatching { json.decodeFromString<BridgeRequest>(message.data.orEmpty()) }.getOrNull()
                ?: return@addWebMessageListener
            when (call.type) {
                "hello" -> upstream.onPageHello(host, replyProxy)
                "request" -> scope.launch {
                    val response = withContext(Dispatchers.IO) { upstream.execute(host, call) }
                    if (webViewRef.get() != null) replyProxy.postMessage(json.encodeToString(response))
                }
                else -> Timber.w("Unknown upstream message ${call.type}")
            }
        }
        scripts[webView] = WebViewCompat.addDocumentStartJavaScript(webView, SCRIPT, rules)
    }

    /** Removes what [attach] added. */
    @SuppressLint("RequiresFeature")
    fun detach(webView: WebView, upstream: FrontendUpstream) {
        val script = scripts.remove(webView) ?: return
        script.remove()
        if (upstream.isSupported) WebViewCompat.removeWebMessageListener(webView, LISTENER)
    }
}
