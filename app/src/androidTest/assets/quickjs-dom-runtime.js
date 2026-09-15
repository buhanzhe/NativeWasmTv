// Experimental synchronous RPC from the QuickJS worker to WebView's UI thread.
// This is deliberately not a complete browser environment.
var __host = NtvCjsBridge, __remote = new Map(), __refs = new WeakMap(), __callbacks = [];
var __timers = [], __timerId = 0;
function __pack(v) {
    if (v === undefined) return {u: 1};
    if (v !== null && (typeof v === 'object' || typeof v === 'function') && __refs.has(v)) return {r: __refs.get(v)};
    if (typeof v === 'function') {
        var id = __callbacks.indexOf(v);
        if (id < 0) { id = __callbacks.length; __callbacks.push(v); }
        return {c: id};
    }
    return {v: v};
}
function __rpc(request) {
    var result = JSON.parse(__host.get(JSON.stringify(request)));
    if (result.error) throw Error(result.error);
    return __unpack(result);
}
function __unpack(v) {
    if (v.u) return undefined;
    if (v.r === undefined) return v.v;
    if (__remote.has(v.r)) return __remote.get(v.r);
    var id = v.r, proxy = new Proxy(v.f ? function () {} : {}, {
        get: function (target, key) {
            if (typeof key === 'symbol') return undefined;
            // Respect non-configurable properties on the local function target.
            var own = Object.getOwnPropertyDescriptor(target, key);
            if (own && !own.configurable && !own.writable && 'value' in own) return own.value;
            return __rpc({op: 'get', id: id, key: key});
        },
        set: function (_, key, value) { return __rpc({op: 'set', id: id, key: key, value: __pack(value)}); },
        apply: function (_, self, args) { return __rpc({op: 'call', id: id, self: __pack(self), args: args.map(__pack)}); },
        construct: function (_, args) { return __rpc({op: 'new', id: id, args: args.map(__pack)}); }
    });
    __remote.set(id, proxy); __refs.set(proxy, id); return proxy;
}
var __browser = __unpack({r: 0}), document = __unpack({r: 1});
__refs.set(globalThis, 0);
var window = globalThis, self = globalThis;
__rpc({op: 'keys', id: 0}).forEach(function (key) {
    if (!(key in globalThis) && key.indexOf('__qdom') !== 0) {
        Object.defineProperty(globalThis, key, {configurable: true,
            get: function () { return __browser[key]; },
            set: function (v) { Object.defineProperty(globalThis, key, {value: v, writable: true, configurable: true}); }
        });
    }
});
function setTimeout(fn, delay) {
    var id = ++__timerId;
    if (__timers.length >= 256) throw Error('experiment timer limit');
    __timers.push({id: id, at: Date.now() + Math.max(0, Number(delay) || 0), fn: fn,
        args: Array.prototype.slice.call(arguments, 2)}); return id;
}
function clearTimeout(id) { __timers = __timers.filter(function (t) { return t.id !== id; }); }
function setInterval(fn, delay) {
    var id = setTimeout.apply(null, arguments);
    __timers[__timers.length - 1].interval = Math.max(20, Number(delay) || 0);
    return id;
}
function clearInterval(id) { clearTimeout(id); }
function __pump() {
    __rpc({op: 'events'}).forEach(function (e) {
        try { __callbacks[e.id].apply(__unpack(e.self), e.args.map(__unpack)); }
        catch (error) { __host.log('callback: ' + error); }
    });
    var now = Date.now(), due = __timers.filter(function (t) { return t.at <= now; });
    __timers = __timers.filter(function (t) { return t.at > now; });
    due.forEach(function (t) {
        if (t.interval) { t.at = now + t.interval; __timers.push(t); }
        try { if (typeof t.fn === 'function') t.fn.apply(window, t.args); else (0, eval)(String(t.fn)); }
        catch (error) { __host.log('timer: ' + error); }
    });
}
