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
      if (queue[0].body.action !== "move" && queue[0].body.action !== "scroll" && queue[0].body.action !== "zoom") { pump(); return; }
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
        // Merge each axis independently. Reversing one axis cancels only that
        // axis, which keeps diagonal Mac-style scrolling responsive.
        var oldX = tail.body.scrollX || 0, nextX = body.scrollX || 0,
          oldY = tail.body.scrollY || 0, nextY = body.scrollY || 0;
        tail.body.scrollX = Math.max(-1440, Math.min(1440,
          oldX * nextX < 0 ? nextX : oldX + nextX));
        tail.body.scrollY = Math.max(-1440, Math.min(1440,
          oldY * nextY < 0 ? nextY : oldY + nextY));
      } else if (!done && tail && !tail.done && body.action === "zoom" && tail.body.action === "zoom") {
        // Relative scales compose by multiplication, preserving small pinch samples.
        tail.body.zoomFactor = Math.max(0.1, Math.min(10, tail.body.zoomFactor * body.zoomFactor));
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
      // Content follows both fingers: swipe up moves page content up, swipe down
      // moves it down. The Android receiver converts this page direction to the
      // opposite AXIS_VSCROLL sign expected by mouse-wheel input.
      remainder -= delta * gain * Math.max(0.75, Math.min(3, scale || 1));
      var value = remainder < 0 ? Math.ceil(remainder) : Math.floor(remainder);
      remainder -= value;
      return value;
    };
  };

  /** Two-finger pan/pinch recognizer. It tracks finger identity so adding,
   * removing or replacing a touch never creates a jump. */
  global.NtvTrackpadGesture = function () {
    var ids = [], startX = 0, startY = 0, lastX = 0, lastY = 0,
      startDistance = 0, lastDistance = 0, lastAt = 0, speed = 0,
      remainderX = 0, remainderY = 0, mode = "";
    function points(touches) {
      var found = {}, i;
      for (i = 0; i < touches.length; i++) found[String(touches[i].identifier)] = touches[i];
      return found;
    }
    function metrics(touches) {
      var found = points(touches), a = found[ids[0]], b = found[ids[1]], dx, dy;
      if (!a || !b) return null;
      dx = b.clientX - a.clientX;
      dy = b.clientY - a.clientY;
      return { x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2,
        distance: Math.sqrt(dx * dx + dy * dy) };
    }
    this.begin = function (touches, time) {
      var dx, dy;
      mode = ""; speed = 0; remainderX = 0; remainderY = 0;
      ids = [String(touches[0].identifier), String(touches[1].identifier)];
      startX = lastX = (touches[0].clientX + touches[1].clientX) / 2;
      startY = lastY = (touches[0].clientY + touches[1].clientY) / 2;
      dx = touches[1].clientX - touches[0].clientX;
      dy = touches[1].clientY - touches[0].clientY;
      startDistance = lastDistance = Math.max(1, Math.sqrt(dx * dx + dy * dy));
      lastAt = time;
    };
    this.update = function (touches, time, scale) {
      if (touches.length !== 2) return null;
      var value = metrics(touches);
      if (!value) { this.begin(touches, time); return null; }
      var totalX = value.x - startX, totalY = value.y - startY,
        pan = Math.sqrt(totalX * totalX + totalY * totalY),
        pinch = Math.abs(value.distance - startDistance), activated = false,
        pinchThreshold = Math.max(20, startDistance * 0.12),
        pinchCandidate = pinch >= pinchThreshold && pinch > pan * 1.4;
      if (!mode) {
        // Ignore spacing jitter; retain continuous fractional zoom after activation.
        if (pinchCandidate) mode = "pinch";
        else if (pan >= 5 && pan >= pinch * 0.7) mode = "scroll";
        else { lastX = value.x; lastY = value.y; lastDistance = value.distance; return null; }
        activated = true;
      }
      if (mode === "pinch") {
        // Include activation travel and retain fractional changes throughout the gesture.
        var factor = value.distance / Math.max(1, activated ? startDistance : lastDistance);
        lastX = value.x; lastY = value.y; lastDistance = value.distance; lastAt = time;
        factor = Math.max(0.1, Math.min(10, Math.pow(factor, 1.25)));
        return factor === 1 ? null : { type: "pinch", factor: factor };
      }
      var elapsed = Math.max(4, Math.min(80, time - lastAt || 16)),
        dx = activated ? totalX : value.x - lastX,
        dy = activated ? totalY : value.y - lastY,
        distance = Math.sqrt(dx * dx + dy * dy);
      lastX = value.x; lastY = value.y; lastDistance = value.distance; lastAt = time;
      if (distance > 180) { speed = 0; remainderX = 0; remainderY = 0; return null; }
      speed += (distance / elapsed - speed) * (1 - Math.exp(-elapsed / 24));
      var gain = (1.35 + Math.min(4.65, speed * 2.7)) * Math.max(0.75, Math.min(3, scale || 1));
      remainderX -= dx * gain;
      // Convert finger travel to content travel. The Android bridge converts this
      // value once more to AXIS_VSCROLL, so an upward two-finger swipe must be
      // positive here to move the webpage upward like a Mac trackpad.
      remainderY -= dy * gain;
      var scrollX = remainderX < 0 ? Math.ceil(remainderX) : Math.floor(remainderX),
        scrollY = remainderY < 0 ? Math.ceil(remainderY) : Math.floor(remainderY);
      remainderX -= scrollX; remainderY -= scrollY;
      return scrollX || scrollY ? { type: "scroll", scrollX: scrollX, scrollY: scrollY } : null;
    };
    this.summary = function (touches) {
      var value = touches && touches.length === 2 ? metrics(touches) : null;
      return { mode: mode, dx: (value ? value.x : lastX) - startX,
        dy: (value ? value.y : lastY) - startY,
        scale: (value ? value.distance : lastDistance) / Math.max(1, startDistance) };
    };
  };
})(window);
