// ES5 only: this side runs in the Android 4.4 WebView. No website script runs here.
(function () {
    var objects = [window, document], events = [], callbacks = {};
    function pack(v) {
        if (v === undefined) return {u: 1};
        if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return {v: v};
        var id = objects.indexOf(v);
        if (id < 0) { id = objects.length; objects.push(v); }
        return {r: id, f: typeof v === 'function'};
    }
    function unpack(v) {
        if (v.u) return undefined;
        if (v.r !== undefined) return objects[v.r];
        if (v.c !== undefined) {
            if (!callbacks[v.c]) callbacks[v.c] = function () {
                if (events.length >= 256) throw Error('experiment event queue limit');
                events.push({id: v.c, self: pack(this), args: Array.prototype.map.call(arguments, pack)});
            };
            return callbacks[v.c];
        }
        return v.v;
    }
    window.__qdom = function (request) {
        try {
            var a = request.args || [], target = objects[request.id], value;
            if (request.op === 'get') value = target[request.key];
            else if (request.op === 'set') {
                if (target.tagName === 'SCRIPT') throw Error('dynamic scripts not supported by experiment');
                target[request.key] = unpack(request.value); value = true;
            } else if (request.op === 'call') {
                value = target.apply(unpack(request.self), a.map(unpack));
            } else if (request.op === 'new') {
                var ctor = Function.prototype.bind.apply(target, [null].concat(a.map(unpack)));
                value = new ctor();
            } else if (request.op === 'events') { value = events; events = []; return JSON.stringify({v: value}); }
            else if (request.op === 'keys') return JSON.stringify({v: Object.getOwnPropertyNames(target)});
            else throw Error('unsupported DOM operation');
            return JSON.stringify(pack(value));
        } catch (e) { return JSON.stringify({error: String(e)}); }
    };
    return 'ready';
}());
