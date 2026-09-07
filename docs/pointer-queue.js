/* Ordered mouse input, ES5 for older Android WebViews. No DOM event simulation. */
(function (global) {
  "use strict";
  global.NtvPointerQueue = function (send, report) {
    var queue = [], busy = false, scheduled = false, mayBeHeld = false;
    function frame(callback) {
      // Own-device bridge already posts to Android's input queue. Waiting for
      // this controller WebView to paint first adds an unrelated display frame.
      // A task still merges a burst; network clients retain VSYNC backpressure.
      if (global.NtvPointerQueue.localTransport) global.setTimeout(callback, 0);
      else if (global.requestAnimationFrame) global.requestAnimationFrame(callback);
      else global.setTimeout(callback, 16);
    }
    function discard(error) {
      var old = queue;
      queue = [];
      for (var i = 0; i < old.length; i++) if (old[i].done) old[i].done(error);
    }
    function pump() {
      if (busy || !queue.length) return;
      var item = queue.shift(), finished = false;
      busy = true;
      if (item.body.action === "down") mayBeHeld = true;
      function finish(error, data) {
        if (finished) return;
        finished = true;
        if (!error && (item.body.action === "up" || item.body.action === "cancel")) mayBeHeld = false;
        // Do not replay stale clicks/drags after a timeout. Release a possibly
        // held button before accepting fresh input; never retry an uncertain click.
        if (error) {
          discard(error);
          if (item.body.action !== "cancel") queue.push({ body: { action: "cancel" } });
          if (report) report(error);
        }
        if (item.done) item.done(error, data);
        busy = false;
        schedule();
      }
      try { send(item.body, finish); } catch (error) { finish(error); }
    }
    function schedule() {
      if (busy || !queue.length) return;
      if (queue[0].body.action !== "move" && queue[0].body.action !== "scroll") { pump(); return; }
      if (scheduled) return;
      scheduled = true;
      frame(function () { scheduled = false; pump(); });
    }
    this.push = function (body, done) {
      var tail = queue.length ? queue[queue.length - 1] : null;
      if (!done && tail && !tail.done && body.action === "move" && tail.body.action === "move") {
        tail.body.dx = Math.max(-240, Math.min(240, tail.body.dx + body.dx));
        tail.body.dy = Math.max(-240, Math.min(240, tail.body.dy + body.dy));
      } else if (!done && tail && !tail.done && body.action === "scroll" && tail.body.action === "scroll") {
        // Reversing direction cancels unsent momentum instead of replaying it later.
        var total = tail.body.scrollY * body.scrollY < 0 ? body.scrollY : tail.body.scrollY + body.scrollY;
        tail.body.scrollY = Math.max(-1440, Math.min(1440, total));
      } else {
        // Bound backlog during network stalls, without dropping just an UP edge.
        if (queue.length >= 64) {
          discard(new Error("飞鼠连接较慢，已取消旧操作"));
          queue.push({ body: { action: "cancel" } });
        }
        queue.push({ body: body, done: done });
      }
      schedule();
    };
    this.reset = function () {
      if (!busy && !queue.length && !mayBeHeld) return;
      discard(new Error("飞鼠操作已取消"));
      queue.push({ body: { action: "cancel" } });
      // Keep the in-flight request as a barrier: cancel must follow DOWN, not race it.
      schedule();
    };
    this.cancelScroll = function () {
      queue = queue.filter(function (item) { return item.body.action !== "scroll"; });
    };
  };

  // Only the in-app, own-origin control page exposes this optional native route.
  // Empty means no local owner: browsers / remote receivers retain HTTP routing.
  global.NtvPointerQueue.sendLocal = function (body, done) {
    if (!global.NtvDevice || !global.NtvDevice.sendPointer) return false;
    var response;
    try { response = global.NtvDevice.sendPointer(JSON.stringify(body)); }
    catch (error) { done(error); return true; } // never resend an uncertain click
    if (!response) { global.NtvPointerQueue.localTransport = false; return false; }
    try {
      var data = JSON.parse(response);
      global.NtvPointerQueue.localTransport = data.ok !== false && data.transport === "local";
      done(data.ok === false ? new Error(data.message || "飞鼠指令失败") : null, data);
    } catch (error) { done(error); }
    return true;
  };

  // Two identified fingers moving vertically together; ES5 for legacy WebViews.
  global.NtvScrollGesture = function () {
    var ids = [], start = {}, lastY = 0, lastAt = 0, speed = 0, direction = 0, remainder = 0;
    this.active = false;
    function map(touches) {
      var result = {};
      for (var i = 0; i < touches.length; i++) result[String(touches[i].identifier)] = touches[i];
      return result;
    }
    this.begin = function (touches, time) {
      this.active = false; speed = 0; direction = 0; remainder = 0;
      ids = [String(touches[0].identifier), String(touches[1].identifier)];
      start = map(touches);
      lastY = (touches[0].clientY + touches[1].clientY) / 2;
      lastAt = time;
    };
    this.update = function (touches, time, scale) {
      if (touches.length !== 2) { this.active = false; return 0; }
      var current = map(touches), a = current[ids[0]], b = current[ids[1]];
      if (!a || !b) { this.begin(touches, time); return 0; }
      if (!this.active) {
        var ay = a.clientY - start[ids[0]].clientY, by = b.clientY - start[ids[1]].clientY,
          ax = a.clientX - start[ids[0]].clientX, bx = b.clientX - start[ids[1]].clientX;
        if (ay * by <= 0 || Math.min(Math.abs(ay), Math.abs(by)) < 3
            || Math.abs(ay) < Math.abs(ax) * 0.8 || Math.abs(by) < Math.abs(bx) * 0.8) return 0;
        this.active = true;
      }
      var center = (a.clientY + b.clientY) / 2, delta = center - lastY,
        elapsed = Math.max(4, Math.min(80, time - lastAt || 16));
      lastY = center; lastAt = time;
      // Touch replacement / discontinuities must not fling an entire webpage.
      if (Math.abs(delta) > 160) { speed = 0; remainder = 0; return 0; }
      if (!delta) return 0;
      var sign = delta < 0 ? -1 : 1;
      if (direction !== sign) { speed = 0; remainder = 0; }
      direction = sign;
      var blend = 1 - Math.exp(-elapsed / 22);
      speed += (Math.abs(delta) / elapsed - speed) * blend;
      var gain = 1.6 + Math.min(5.4, speed * 2.8);
      // Negative finger movement -> negative wheel delta -> page scrolls upward.
      remainder += delta * gain * Math.max(0.75, Math.min(3, scale || 1));
      var value = remainder < 0 ? Math.ceil(remainder) : Math.floor(remainder);
      remainder -= value;
      return value;
    };
  };
})(window);
