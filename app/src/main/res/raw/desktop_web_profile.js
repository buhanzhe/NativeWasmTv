function (config) {
    'use strict';
    // No polling, canvas redraws, native bridge, or changes to media/input events.
    // Screen values describe nTv's virtual desktop, not the physical phone panel.
    var w = window, n = navigator;
    if (w.__ntvDesktopProfile) {
        w.__ntvDesktopProfile.config = config;
        return;
    }
    var state = {config: config};
    Object.defineProperty(w, '__ntvDesktopProfile', {value: state, configurable: true});
    function get(object, name, read) {
        if (!object) return;
        try { Object.defineProperty(object, name, {get: read, configurable: true}); } catch (ignored) {}
    }
    function value(object, name, v) { get(object, name, function () { return v; }); }
    var np = Object.getPrototypeOf(n);
    get(np, 'platform', function () { return state.config.mac ? 'MacIntel' : 'Win32'; });
    value(np, 'vendor', 'Google Inc.');
    value(np, 'maxTouchPoints', 0);
    value(np, 'hardwareConcurrency', 8);
    if ('deviceMemory' in n) value(np, 'deviceMemory', 8);

    // WebSettings supplies UA/appVersion; native metadata supplies Client Hints
    // for both requests and JS (including Workers). Do not shadow them with a
    // second, inconsistent implementation. Unsupported engines expose no JS hints.
    if (!config.nativeMetadata && 'userAgentData' in n) value(np, 'userAgentData', undefined);

    var sp = w.Screen ? w.Screen.prototype : w.screen;
    ['width', 'availWidth'].forEach(function (key) {
        get(sp, key, function () { return state.config.width; });
    });
    ['height', 'availHeight'].forEach(function (key) {
        get(sp, key, function () { return state.config.height; });
    });
    ['availLeft', 'availTop'].forEach(function (key) { value(sp, key, 0); });
    value(sp, 'colorDepth', 24);
    value(sp, 'pixelDepth', 24);
    get(w, 'outerWidth', function () { return state.config.width; });
    get(w, 'outerHeight', function () { return state.config.height; });
    get(w, 'devicePixelRatio', function () { return state.config.scale; });
    if (w.screen.orientation) {
        get(w.screen.orientation, 'type', function () {
            return state.config.width >= state.config.height ? 'landscape-primary' : 'portrait-primary';
        });
        value(w.screen.orientation, 'angle', 0);
    }
    // Multiple physical displays and phone-only telemetry are not needed to play video.
    value(w, 'getScreenDetails', undefined);
    value(np, 'gpu', undefined);
    ['getBattery', 'webkitGetBattery', 'mozGetBattery', 'battery', 'webkitBattery', 'mozBattery']
        .forEach(function (key) { if (key in n) value(np, key, undefined); });
    ['connection', 'mozConnection', 'webkitConnection'].forEach(function (key) {
        if (key in n) value(np, key, undefined);
    });
    ['Accelerometer', 'LinearAccelerationSensor', 'GravitySensor', 'Gyroscope',
        'Magnetometer', 'AbsoluteOrientationSensor', 'RelativeOrientationSensor',
        'AmbientLightSensor'].forEach(function (key) { if (key in w) value(w, key, undefined); });
    ['deviceorientation', 'deviceorientationabsolute', 'devicemotion'].forEach(function (type) {
        w.addEventListener(type, function (event) { event.stopImmediatePropagation(); }, true);
    });

    // Withhold the optional identifying-driver extension, not the renderer itself.
    // A made-up Intel/D3D string contradicts Android's actual GL capabilities.
    // Keep getParameter native, including INVALID_ENUM for unavailable extensions.
    // This is document privacy, NOT full GPU/canvas/Worker fingerprint isolation.
    function protectGl(type) {
        if (!type) return;
        var proto = type.prototype, extension = proto.getExtension, supported = proto.getSupportedExtensions;
        function identifying(name) { return String(name).toLowerCase() === 'webgl_debug_renderer_info'; }
        proto.getExtension = function (name) {
            if (identifying(name)) {
                supported.call(this); // Preserve native receiver validation.
                return null;
            }
            return extension.apply(this, arguments);
        };
        proto.getSupportedExtensions = function () {
            var list = supported.apply(this, arguments);
            return list && list.filter(function (name) { return !identifying(name); });
        };
    }
    protectGl(w.WebGLRenderingContext);
    protectGl(w.WebGL2RenderingContext);
}
