// Page side of FrontendUpstream (app/src/main/kotlin/.../frontend/upstream/FrontendUpstream.kt).
// Injected at document start into the app-internal frontend origin only. The page origin never changes;
// this script points what the WebView cannot intercept at the server URL that is currently in use:
// - WebSockets to the page origin go to the current server. The Home Assistant WebSocket is moved to
//   a new server (or reconnected after a network break) by the script itself: it logs in on a new
//   connection, repeats the page's subscriptions there and only then lets go of the old one, so the
//   page never sees a disconnect.
// - fetch and XMLHttpRequest calls with a body go through the app, because the WebView's request
//   interception cannot read request bodies.
// - Links copied or shared from the page use the server's real address instead of the internal one.
(() => {
  if (window.__haUpstream) return;
  const channel = window.androidInternalHaUpstream;
  if (!channel) return;
  window.__haUpstream = true;

  const HA_WEBSOCKET_PATH = '/api/websocket';
  const INGRESS_PATH = '/api/hassio_ingress/';
  const MOVE_TIMEOUT_MS = 8000;
  const RECOVER_FOR_MS = 15000;
  const RECOVER_RETRY_MS = 1000;
  const DRAIN_TIMEOUT_MS = 1500;
  const DRAIN_POLL_MS = 50;
  const CHUNK_BYTES = 512 * 1024;
  const SOCKET_ID_BYTES = 4;
  const NORMAL_CLOSURE = 1000;
  const ABNORMAL_CLOSURE = 1006;

  const NativeWebSocket = window.WebSocket;
  const nativeFetch = window.fetch.bind(window);
  const sockets = new Set();
  const appSockets = new Map();
  let nextSocketId = 0;
  let binaryChannel = false;
  const replies = new Map();
  let wsBase = null;
  let publicOrigin = null;
  let waiting = [];
  let nextId = 0;

  const ask = (message) => new Promise((resolve) => {
    const id = ++nextId;
    replies.set(id, resolve);
    channel.postMessage(JSON.stringify({ ...message, id }));
  });

  const parse = (text) => {
    if (typeof text !== 'string') return null;
    try { return JSON.parse(text); } catch (e) { return null; }
  };

  const toUpstream = (raw) => {
    const url = new URL(raw, document.baseURI);
    if (url.host !== location.host) return url.href;
    const base = new URL(wsBase);
    url.protocol = base.protocol;
    url.host = base.host;
    return url.href;
  };

  /** Subscriptions are commands that keep sending events after their result. */
  // Subscriptions are recognized by name. Commands that stream events under another name start an action
  // (assist_pipeline/run, zwave_js/add_node, camera/webrtc/offer): repeating them would run it again.
  // camera/stream is a one-shot request.
  const isSubscription = (type) => !/unsubscribe/.test(type || '')
    && /subscribe|^history\/stream$|^logbook\/event_stream$|^render_template$/.test(type || '');

  /** Base for both kinds of socket: a WebSocket look-alike that the page holds on to. */
  class ProxySocket extends EventTarget {
    constructor(url, protocols) {
      super();
      this._url = new URL(url, document.baseURI).href;
      this._protocols = protocols;
      this._closed = false;
      this._opened = false;
      this._native = null;
      this._binaryType = 'blob';
      sockets.add(this);
      if (wsBase) this._start(); else waiting.push(this);
    }
    _emit(type, init) {
      if (this._closed) return;
      if (type === 'close') { this._closed = true; sockets.delete(this); }
      const event = type === 'message' ? new MessageEvent('message', init)
        : type === 'close' ? new CloseEvent('close', init)
        : new Event(type);
      this.dispatchEvent(event);
    }
    _connect() {
      const target = toUpstream(this._url);
      const ws = this._protocols === undefined ? new NativeWebSocket(target) : new NativeWebSocket(target, this._protocols);
      ws.binaryType = this._binaryType;
      return ws;
    }
    _listen(ws) {
      ws.addEventListener('open', () => {
        if (ws !== this._native || this._opened) return;
        this._opened = true;
        this._emit('open');
      });
      ws.addEventListener('message', (e) => { if (ws === this._native) this._onMessage(e); });
      ws.addEventListener('error', () => { if (ws === this._native) this._onError(); });
      ws.addEventListener('close', (e) => { if (ws === this._native) this._onClose(e); });
    }
    _onMessage(e) { this._emit('message', { data: e.data, origin: e.origin }); }
    _onError() { this._emit('error'); }
    _onClose(e) { this._emit('close', { code: e.code, reason: e.reason, wasClean: e.wasClean }); }
    onSwitch() {}
    get url() { return this._url; }
    get readyState() { return this._closed ? 3 : (this._opened ? 1 : 0); }
    get protocol() { return this._native ? this._native.protocol : ''; }
    get extensions() { return this._native ? this._native.extensions : ''; }
    get bufferedAmount() { return this._native ? this._native.bufferedAmount : 0; }
    get binaryType() { return this._binaryType; }
    set binaryType(value) { this._binaryType = value; if (this._native) this._native.binaryType = value; }
    send(data) {
      if (!this._native || this._native.readyState !== 1) throw new DOMException('WebSocket is not open', 'InvalidStateError');
      this._native.send(data);
    }
    close(code, reason) {
      if (this._native) this._native.close(code, reason);
      else this._emit('close', { code: code || NORMAL_CLOSURE, reason: reason || '', wasClean: true });
    }
  }

  // on<type> handlers are listeners, as on a native socket: event.target is set, the order follows
  // registration, and a throwing page handler cannot break the script's own work
  for (const type of ['open', 'message', 'error', 'close']) {
    Object.defineProperty(ProxySocket.prototype, `on${type}`, {
      get() { return (this._handlers && this._handlers[type]) || null; },
      set(value) {
        if (!this._handlers) this._handlers = {};
        if (!(type in this._handlers)) {
          this.addEventListener(type, (event) => {
            const handler = this._handlers[type];
            if (typeof handler === 'function') handler.call(this, event);
          });
        }
        this._handlers[type] = typeof value === 'function' ? value : null;
      },
    });
  }

  /** Any other WebSocket on the page origin (camera streams): opened by the WebView to the current server. */
  class PlainSocket extends ProxySocket {
    _start() {
      if (this._closed) return;
      this._native = this._connect();
      this._listen(this._native);
    }
  }

  /**
   * WebSockets of add-on panels: the app opens them, because add-ons behind ingress (such as go2rtc's
   * stream viewer) reject a connection whose Origin is not the server's own address.
   */
  class AppSocket extends ProxySocket {
    _start() {
      if (this._closed) return;
      this._sid = ++nextSocketId;
      appSockets.set(this._sid, this);
      this._sending = Promise.resolve();
      const url = new URL(this._url);
      channel.postMessage(JSON.stringify({ type: 'wsOpen', sid: this._sid, path: url.pathname + url.search, protocols: [].concat(this._protocols || []) }));
    }
    get protocol() { return this._protocol || ''; }
    get extensions() { return ''; }
    get bufferedAmount() { return 0; }
    send(data) {
      if (!this._opened || this._closed) throw new DOMException('WebSocket is not open', 'InvalidStateError');
      const sid = this._sid;
      if (typeof data === 'string') {
        this._sending = this._sending.then(() => channel.postMessage(JSON.stringify({ type: 'wsSend', sid, text: data }))).catch(() => {});
        return;
      }
      // Blobs are read asynchronously; the chain keeps the frame order
      this._sending = this._sending.then(async () => {
        const bytes = new Uint8Array(data instanceof Blob ? await data.arrayBuffer() : ArrayBuffer.isView(data) ? data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) : data);
        if (binaryChannel) {
          const framed = new Uint8Array(SOCKET_ID_BYTES + bytes.length);
          new DataView(framed.buffer).setInt32(0, sid);
          framed.set(bytes, SOCKET_ID_BYTES);
          channel.postMessage(framed.buffer);
        } else {
          channel.postMessage(JSON.stringify({ type: 'wsSend', sid, b64: toBase64(bytes) }));
        }
      }).catch(() => {}); // one unreadable Blob does not block later frames
    }
    close(code, reason) {
      if (code !== undefined && code !== NORMAL_CLOSURE && (code < 3000 || code > 4999)) {
        throw new DOMException('The close code must be either 1000, or between 3000 and 4999.', 'InvalidAccessError');
      }
      if (reason !== undefined && new TextEncoder().encode(reason).length > 123) {
        throw new DOMException('The close reason must not be greater than 123 bytes.', 'SyntaxError');
      }
      if (this._closed || this._closing) return;
      this._closing = true;
      if (this._sid === undefined) { super.close(code, reason); return; }
      const sid = this._sid;
      const post = () => channel.postMessage(JSON.stringify({ type: 'wsClose', sid, code: code || NORMAL_CLOSURE, reason: reason || '' }));
      // After the frames already queued
      this._sending = this._sending.then(post, post);
    }
    _deliver(data) {
      if (typeof data !== 'string' && this._binaryType === 'blob') data = new Blob([data]);
      this._emit('message', { data, origin: location.origin });
    }
    _fromApp(message) {
      if (message.type === 'wsOpened') {
        // A socket closed while connecting ends with the app's wsClosed: error and close, as a native one
        if (this._closing) return;
        this._protocol = message.protocol;
        this._opened = true;
        this._emit('open');
      }
      else if (message.type === 'wsText') this._deliver(message.b64 !== undefined && message.b64 !== null ? fromBase64(message.b64).buffer : message.text);
      else if (message.type === 'wsClosed') {
        appSockets.delete(this._sid);
        if (!this._opened) this._emit('error');
        this._emit('close', { code: message.code, reason: message.reason, wasClean: message.code === NORMAL_CLOSURE });
      }
    }
  }

  /** The Home Assistant WebSocket: survives server switches and network breaks without the page noticing. */
  class HaSocket extends ProxySocket {
    _start() {
      if (this._closed) return;
      this._authMessage = null;
      this._authed = false;
      this._pageClosing = false;
      this._features = null;
      this._subscriptions = new Map();
      this._pending = new Map();
      this._skipResults = new Set();
      this._outbox = [];
      this._holding = false;
      this._moving = null;
      this._abortMove = null;
      this._native = this._connect();
      this._host = new URL(this._native.url).host;
      this._listen(this._native);
    }

    send(data) {
      if (this._closed || !this._pending) throw new DOMException('WebSocket is not open', 'InvalidStateError');
      // Held messages are recorded when they really go out, so a hand-over never fails one it then sends
      if (this._holding || !this._native || this._native.readyState !== 1) { this._outbox.push(data); return; }
      const message = parse(data);
      if (message && !Array.isArray(message)) {
        if (message.type === 'auth') {
          this._authMessage = data;
        } else if (message.id !== undefined) {
          if (message.type === 'supported_features') this._features = data;
          if (message.type === 'unsubscribe_events') {
            this._subscriptions.delete(message.subscription);
          }
          this._pending.set(message.id, { raw: data, type: message.type });
        }
      }
      this._native.send(data);
    }

    _flush() {
      const queued = this._outbox;
      this._outbox = [];
      queued.forEach((data) => this.send(data));
    }

    close(code, reason) {
      this._pageClosing = true;
      if (this._abortMove) this._abortMove();
      super.close(code, reason);
    }

    _onMessage(e) {
      const parsed = parse(e.data);
      if (!parsed) { super._onMessage(e); return; }
      const all = Array.isArray(parsed) ? parsed : [parsed];
      const kept = all.filter((message) => this._track(message));
      if (kept.length === all.length) super._onMessage(e);
      else if (kept.length) super._onMessage({ data: JSON.stringify(Array.isArray(parsed) ? kept : kept[0]), origin: e.origin });
    }

    /** Follows the conversation so it can be repeated on a new connection. Returns false to hide a message. */
    _track(message) {
      if (message.type === 'auth_ok') this._authed = true;
      if (message.type === 'result' && this._skipResults.delete(message.id)) return false;
      if (message.type === 'result' || message.type === 'pong') {
        const sent = this._pending.get(message.id);
        if (sent) {
          this._pending.delete(message.id);
          if (message.type === 'result' && message.success && isSubscription(sent.type)) this._subscriptions.set(message.id, sent.raw);
        }
      }
      return true;
    }

    _onError() {
      if (!this._authed) super._onError();
    }

    _onClose(e) {
      // A clean close comes from Home Assistant itself (a restart): the page reconnects and refreshes its
      // data, as without this script
      if (this._pageClosing || !this._authed || e.wasClean) { super._onClose(e); return; }
      // The network went away under the connection: reconnect quietly before telling the page
      this._native = null;
      this._holding = true;
      this._recover(Date.now() + RECOVER_FOR_MS);
    }

    async _recover(deadline) {
      if (this._closed || this._native) return;
      if (await this._move()) return;
      if (Date.now() < deadline) setTimeout(() => this._recover(deadline), RECOVER_RETRY_MS);
      else this._emit('close', { code: ABNORMAL_CLOSURE, reason: 'Connection lost', wasClean: false });
    }

    onSwitch() {
      // A socket still waiting for the app's base has nothing to move
      if (this._closed || this._pageClosing || !this._pending) return;
      if (!this._authed) {
        // Not logged in yet: start over on the new server, the page simply waits a little longer
        if (this._authMessage) return;
        const old = this._native;
        const ws = this._connect();
        this._native = ws;
        this._host = new URL(ws.url).host;
        this._listen(ws);
        // The page may send its login while the new connection is still opening
        ws.addEventListener('open', () => { if (ws === this._native) this._flush(); });
        if (old) old.close();
        return;
      }
      if (this._abortMove) this._abortMove();
      this._move();
    }

    /** Opens and logs in a connection to the current server, then hands the conversation over to it. */
    _move() {
      if (this._moving) return this._moving;
      const target = wsBase;
      this._moving = new Promise((resolve) => {
        const ws = this._connect();
        let settled = false;
        const settle = (ok) => {
          if (settled) return;
          settled = true;
          this._abortMove = null;
          clearTimeout(timer);
          if (!ok) { try { ws.close(); } catch (e) {} }
          resolve(ok);
        };
        const timer = setTimeout(() => settle(false), MOVE_TIMEOUT_MS);
        // A switch, or the page closing its socket, gives up an attempt that has not logged in
        this._abortMove = () => settle(false);
        ws.onmessage = async (e) => {
          const parsed = parse(e.data);
          for (const message of [].concat(parsed || [])) {
            if (message.type === 'auth_required') {
              const reply = await ask({ type: 'token' });
              if (!reply.token || settled || this._closed || this._pageClosing) { settle(false); return; }
              const auth = parse(this._authMessage) || {};
              ws.send(JSON.stringify({ ...auth, type: 'auth', access_token: reply.token }));
            } else if (message.type === 'auth_invalid') {
              settle(false);
            } else if (message.type === 'auth_ok') {
              if (settled) return;
              this._abortMove = null;
              clearTimeout(timer);
              settle(await this._handOver(ws));
            }
          }
        };
        ws.onclose = () => settle(false);
        ws.onerror = () => settle(false);
      }).finally(() => {
        this._moving = null;
        // The app switched again while this attempt went to the previous server URL
        if (target !== wsBase && this._authed && !this._closed && !this._pageClosing
          && (!this._native || new URL(wsBase).host !== this._host)) this._move();
      });
      return this._moving;
    }

    /** Returns false when the new connection cannot take over; the page then stays where it is. */
    async _handOver(ws) {
      ws.onmessage = ws.onclose = ws.onerror = null;
      const old = this._native;
      const fromHost = this._host;
      this._holding = true;
      // Commands already sent on a working old connection get their answers there first
      const drainUntil = Date.now() + DRAIN_TIMEOUT_MS;
      while (ws.readyState === 1 && old && old.readyState === 1 && this._hasOpenCommands() && Date.now() < drainUntil) {
        await new Promise((r) => setTimeout(r, DRAIN_POLL_MS));
      }
      if (this._closed || this._pageClosing) return false;
      if (ws.readyState !== 1) {
        // The new connection died while the old one finished its commands: stay where the page is
        this._holding = !this._native;
        if (!this._holding) this._flush();
        return false;
      }
      this._native = ws;
      this._listen(ws);
      // Repeat what makes up the page's session, in the original order (ids must keep increasing).
      // Subscriptions still waiting for their result are sent again, as home-assistant-js-websocket
      // does after a reconnect; the page gets their result from the new connection.
      this._skipResults = new Set();
      const resend = new Map([...this._pending].filter(([, sent]) => sent.raw !== this._features && isSubscription(sent.type)));
      const replay = [...this._subscriptions, ...[...resend].map(([id, sent]) => [id, sent.raw])].sort((a, b) => a[0] - b[0]);
      if (this._features) replay.unshift([parse(this._features).id, this._features]);
      for (const [id, raw] of replay) {
        if (!resend.has(id)) this._skipResults.add(id);
        ws.send(raw);
      }
      // Other commands whose answer was lost with the old connection fail, as they would on a reconnect.
      // They are never sent again: they may have run already. Pings are answered: the new connection
      // just logged in.
      for (const [id, sent] of this._pending) {
        if (sent.raw === this._features || resend.has(id)) continue;
        const answer = sent.type === 'ping' ? { id, type: 'pong' }
          : { id, type: 'result', success: false, error: { code: 'connection_lost', message: 'Connection lost' } };
        this._emit('message', { data: JSON.stringify(answer) });
      }
      this._pending = resend;
      this._holding = false;
      this._flush();
      if (old) { try { old.close(); } catch (e) {} }
      // Tell the app when the connection now runs on another server, however it got there (an announced
      // switch, or a reconnect after the old network went away)
      this._host = new URL(ws.url).host;
      if (fromHost && fromHost !== this._host) channel.postMessage(JSON.stringify({ type: 'moved' }));
      return true;
    }

    _hasOpenCommands() {
      for (const sent of this._pending.values()) if (sent.raw !== this._features) return true;
      return false;
    }
  }

  window.WebSocket = function WebSocket(url, protocols) {
    if (!new.target) throw new TypeError("Failed to construct 'WebSocket': Please use the 'new' operator");
    const parsed = new URL(url, document.baseURI);
    if (parsed.host !== location.host) return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
    if (parsed.pathname === HA_WEBSOCKET_PATH) return new HaSocket(url, protocols);
    return parsed.pathname.startsWith(INGRESS_PATH) ? new AppSocket(url, protocols) : new PlainSocket(url, protocols);
  };
  window.WebSocket.prototype = NativeWebSocket.prototype;
  for (const [name, value] of [['CONNECTING', 0], ['OPEN', 1], ['CLOSING', 2], ['CLOSED', 3]]) {
    window.WebSocket[name] = value;
    ProxySocket.prototype[name] = value;
  }
  // instanceof WebSocket keeps working for the look-alikes
  Object.defineProperty(window.WebSocket, Symbol.hasInstance, {
    value: (object) => object instanceof NativeWebSocket || object instanceof ProxySocket,
  });

  // Requests with a body
  const toBase64 = (bytes) => {
    let text = '';
    for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(text);
  };
  const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));

  const bridgedFetch = async (request) => {
    const url = new URL(request.url);
    // A Blob, which Chromium can keep on disk for a large file
    const body = await request.blob();
    const call = { type: 'request', method: request.method, path: url.pathname + url.search, headers: [...request.headers.entries()] };
    if (body.size > CHUNK_BYTES) {
      // Large uploads (backups, media) go in pieces to a file in the app, each one after the app stored the last
      call.uploadId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      for (let i = 0; i < body.size; i += CHUNK_BYTES) {
        const bytes = new Uint8Array(await body.slice(i, i + CHUNK_BYTES).arrayBuffer());
        const stored = await ask({ type: 'chunk', uploadId: call.uploadId, body: toBase64(bytes) });
        if (stored.error) throw new TypeError(stored.error);
      }
    } else if (body.size) {
      call.body = toBase64(new Uint8Array(await body.arrayBuffer()));
    }
    const reply = await ask(call);
    if (reply.error) throw new TypeError(reply.error);
    const noBody = [101, 204, 205, 304].includes(reply.status);
    return new Response(noBody ? null : fromBase64(reply.body || ''), { status: reply.status, statusText: reply.statusText, headers: reply.headers });
  };

  const needsBridge = (method, url) => method !== 'GET' && method !== 'HEAD' && new URL(url, document.baseURI).host === location.host;

  window.fetch = function fetch(input, init) {
    try {
      const method = ((init && init.method) || (input instanceof Request ? input.method : 'GET')).toUpperCase();
      const url = input instanceof Request ? input.url : String(input);
      if (!needsBridge(method, url)) return nativeFetch(input, init);
      return bridgedFetch(new Request(input, init));
    } catch (e) {
      // Bad input rejects, as with the native fetch
      return Promise.reject(e);
    }
  };

  const xhr = XMLHttpRequest.prototype;
  const nativeOpen = xhr.open;
  const nativeSend = xhr.send;
  const nativeSetRequestHeader = xhr.setRequestHeader;
  const bridgedProperties = ['readyState', 'status', 'statusText', 'response', 'responseText', 'responseURL', 'getResponseHeader', 'getAllResponseHeaders'];
  xhr.open = function (method, url, ...rest) {
    // A reused request reports its new request, not what a bridged send set before
    for (const name of bridgedProperties) delete this[name];
    const upper = String(method).toUpperCase();
    this.__haBridge = needsBridge(upper, url) ? { method: upper, url: new URL(url, document.baseURI).href, headers: new Headers() } : null;
    return nativeOpen.call(this, method, url, ...rest);
  };
  xhr.setRequestHeader = function (name, value) {
    if (this.__haBridge) this.__haBridge.headers.append(name, value);
    return nativeSetRequestHeader.call(this, name, value);
  };
  xhr.send = function (body) {
    const call = this.__haBridge;
    if (!call) return nativeSend.call(this, body);
    const request = this;
    const set = (name, value) => Object.defineProperty(request, name, { configurable: true, get: () => value });
    const fire = (target, type) => target.dispatchEvent(new ProgressEvent(type));
    bridgedFetch(new Request(call.url, { method: call.method, headers: call.headers, body })).then(async (response) => {
      const buffer = await response.arrayBuffer();
      const text = new TextDecoder().decode(buffer);
      const type = request.responseType;
      let json = null;
      if (type === 'json') { try { json = JSON.parse(text); } catch (e) {} }
      set('readyState', 4);
      set('status', response.status);
      set('statusText', response.statusText);
      set('responseURL', call.url);
      set('responseText', text);
      set('response', type === 'json' ? json : type === 'arraybuffer' ? buffer : type === 'blob' ? new Blob([buffer], { type: response.headers.get('content-type') || '' }) : text);
      request.getResponseHeader = (name) => response.headers.get(name);
      request.getAllResponseHeaders = () => [...response.headers].map(([k, v]) => `${k}: ${v}`).join('\r\n');
      fire(request.upload, 'progress');
      fire(request.upload, 'load');
      fire(request, 'readystatechange');
      fire(request, 'load');
      fire(request, 'loadend');
    }, () => {
      set('readyState', 4);
      set('status', 0);
      fire(request, 'readystatechange');
      fire(request, 'error');
      fire(request, 'loadend');
    });
  };

  // Links leaving the app use the server's real address
  const toPublic = (value) => (typeof value === 'string' && publicOrigin ? value.split(location.origin).join(publicOrigin) : value);
  if (navigator.clipboard && navigator.clipboard.writeText) {
    const writeText = navigator.clipboard.writeText.bind(navigator.clipboard);
    navigator.clipboard.writeText = (text) => writeText(toPublic(text));
  }
  if (navigator.share) {
    const share = navigator.share.bind(navigator);
    navigator.share = (data) => share(data ? { ...data, url: toPublic(data.url), text: toPublic(data.text) } : data);
  }

  // The WebView closes a page's own WebSockets when the page goes away; the app's ones must be told
  addEventListener('pagehide', () => {
    for (const sid of appSockets.keys()) channel.postMessage(JSON.stringify({ type: 'wsClose', sid, code: NORMAL_CLOSURE, reason: '' }));
  });

  channel.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer) {
      // A binary frame for an AppSocket: the socket id, then the payload
      const socket = appSockets.get(new DataView(event.data).getInt32(0));
      if (socket) socket._deliver(event.data.slice(SOCKET_ID_BYTES));
      return;
    }
    const message = parse(event.data);
    if (!message) return;
    if (message.sid !== undefined && appSockets.has(message.sid)) { appSockets.get(message.sid)._fromApp(message); return; }
    if (message.type === 'base') {
      wsBase = message.ws;
      publicOrigin = message.publicOrigin;
      binaryChannel = !!message.binary;
      const queued = waiting;
      waiting = [];
      for (const socket of queued) {
        // One socket that cannot start does not stop the others
        try {
          socket._start();
        } catch (e) {
          socket._emit('error');
          socket._emit('close', { code: ABNORMAL_CLOSURE, reason: '', wasClean: false });
        }
      }
    } else if (message.type === 'switch') {
      wsBase = message.ws;
      [...sockets].forEach((socket) => socket.onSwitch());
    } else if (message.type === 'response') {
      const resolve = replies.get(message.id);
      if (!resolve) return;
      replies.delete(message.id);
      resolve(message);
    }
  };
  channel.postMessage(JSON.stringify({ type: 'hello' }));
})();
