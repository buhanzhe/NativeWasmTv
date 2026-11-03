var playlistGroups = [], groupSignature = '', groupBusy = false, groupDrag = null,
  channelEditor = null, channelLoad = null, channelLoadSequence = 0, channelRowHeight = 60;
function groupElement(tag, className, text) {
  var el = document.createElement(tag);
  if (className) el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}
function renderPlaylistGroupSettings() {
  var list = document.getElementById('playlistGroupSettings');
  list.innerHTML = '';
  if (!playlistGroups.length) {
    list.appendChild(groupElement('p', 'hint', '当前已启用的频道源中没有可管理的分组。')); return;
  }
  var fragment = document.createDocumentFragment();
  for (var i = 0; i < playlistGroups.length; i++) {
    var group = playlistGroups[i], card = groupElement('div', 'group-card'),
      item = groupElement('div', 'group-item'), copy = groupElement('div', 'group-copy'),
      enabled = document.createElement('input'), manage = groupElement('button', 'group-manage', '管理频道'),
      handle = groupElement('button', 'channel-drag-handle group-drag-handle');
    handle.type = 'button'; handle._index = i; handle._card = card;
    handle.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="8" cy="5" r="1.6"/><circle cx="16" cy="5" r="1.6"/><circle cx="8" cy="12" r="1.6"/><circle cx="16" cy="12" r="1.6"/><circle cx="8" cy="19" r="1.6"/><circle cx="16" cy="19" r="1.6"/></svg>';
    handle.setAttribute('aria-label', '拖动排序 ' + group.name);
    handle.title = '拖动排序，也可用上下方向键移动';
    handle.onpointerdown = window.PointerEvent ? beginGroupDrag : null;
    handle.onmousedown = !window.PointerEvent ? beginGroupDrag : null;
    handle.ontouchstart = !window.PointerEvent ? beginGroupDrag : null;
    handle.onkeydown = function (event) {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        event.preventDefault(); movePlaylistGroup(this._index, this._index + (event.key === 'ArrowUp' ? -1 : 1), 'handle');
      }
    };
    copy.appendChild(groupElement('b', '', group.name || '未命名分组'));
    copy.appendChild(groupElement('span', '', String(group.channelCount || 0) + ' 个频道'));
    enabled.type = 'checkbox'; enabled.className = 'source-toggle'; enabled.checked = group.enabled !== false;
    enabled.setAttribute('aria-label', '启用 ' + (group.name || '频道分组')); enabled._group = group;
    enabled.onchange = function () { savePlaylistGroupState(this); };
    manage.type = 'button'; manage._group = group; manage._card = card;
    manage.setAttribute('aria-expanded', 'false'); manage.onclick = function () { openGroupChannels(this); };
    item.appendChild(handle); item.appendChild(copy); item.appendChild(manage); item.appendChild(enabled); card.appendChild(item); fragment.appendChild(card);
  }
  list.appendChild(fragment);
}
function setGroupBusy(busy) {
  if (groupBusy === busy) return;
  groupBusy = busy;
  var controls = document.getElementById('playlistGroupSettings').querySelectorAll('button, .source-toggle');
  for (var i = 0; i < controls.length; i++) {
    if (busy) { controls[i]._wasDisabled = controls[i].disabled; controls[i].disabled = true; }
    else controls[i].disabled = !!controls[i]._wasDisabled;
  }
}
function movePlaylistGroup(from, target, direction) {
  if (groupBusy || from < 0 || target < 0 || from >= playlistGroups.length || target >= playlistGroups.length || from === target) return;
  if (channelEditor && channelEditor.dirty) { toast('请先保存或取消频道修改', true); return; }
  var groups = playlistGroups.slice(), moved = groups.splice(from, 1)[0]; groups.splice(target, 0, moved);
  var previous = playlistGroups;
  closeGroupChannels(); playlistGroups = groups; renderPlaylistGroupSettings(); setGroupBusy(true);
  api('/api/settings', { playlistGroupOrder: groups.map(function (group) { return group.id; }) }, function (error, result) {
    setGroupBusy(false);
    if (error) { playlistGroups = previous; renderPlaylistGroupSettings(); toast(error.message, true); refresh(); return; }
    groupSignature = JSON.stringify(groups);
    var buttons = document.getElementById('playlistGroupSettings').querySelectorAll('.group-drag-handle'), focus = buttons[target];
    if (focus && !focus.disabled) focus.focus();
    refresh(); toast(result.message || '分组顺序已保存');
  });
}
function beginGroupDrag(event) {
  if (groupBusy || groupDrag || (event.button !== undefined && event.button !== 0)) return;
  if (channelEditor && channelEditor.dirty) { toast('请先保存或取消频道修改', true); return; }
  if (event.touches && event.touches.length !== 1) return;
  closeGroupChannels();
  var touch = event.touches ? event.touches[0] : event, list = document.getElementById('playlistGroupSettings'), cards = Array.prototype.slice.call(list.children), rects = [];
  for (var i = 0; i < cards.length; i++) { var rect = cards[i].getBoundingClientRect(); rects.push({top:rect.top + window.pageYOffset, height:rect.height}); }
  groupDrag = {from:this._index, target:this._index, handle:this, card:this._card, cards:cards, rects:rects,
    startY:touch.clientY + window.pageYOffset, y:touch.clientY, pointerId:event.pointerId};
  this._card.className += ' group-sorting';
  if (event.pointerId !== undefined && this.setPointerCapture) this.setPointerCapture(event.pointerId);
  if (window.PointerEvent) {
    document.addEventListener('pointermove', moveGroupDrag, false); document.addEventListener('pointerup', finishGroupDrag, false); document.addEventListener('pointercancel', cancelGroupDrag, false);
  } else {
    document.addEventListener('mousemove', moveGroupDrag, false); document.addEventListener('mouseup', finishGroupDrag, false);
    document.addEventListener('touchmove', moveGroupDrag, false); document.addEventListener('touchend', finishGroupDrag, false); document.addEventListener('touchcancel', cancelGroupDrag, false);
  }
  groupDrag.frame = requestAnimationFrame(groupDragFrame); event.preventDefault();
}
function moveGroupDrag(event) {
  var drag = groupDrag; if (!drag || (event.pointerId !== undefined && event.pointerId !== drag.pointerId)) return;
  if (event.touches && event.touches.length !== 1) { endGroupDrag(true); return; }
  drag.y = (event.touches ? event.touches[0] : event).clientY;
  if (event.cancelable) event.preventDefault();
}
function groupDropTarget(drag) {
  var center = drag.rects[drag.from].top + drag.rects[drag.from].height/2 + drag.y + window.pageYOffset - drag.startY, target = 0;
  for (var i=0;i<drag.rects.length;i++) {
    var other = drag.rects[i].top + drag.rects[i].height/2;
    if (i!==drag.from && (center>other || i>drag.from && Math.abs(center-other)<1)) target++;
  }
  return target;
}
function groupDragFrame() {
  var drag = groupDrag; if (!drag) return;
  var edge = 64, speed = drag.y < edge ? -Math.ceil((edge-drag.y)/6) : drag.y > innerHeight-edge ? Math.ceil((drag.y-innerHeight+edge)/6) : 0;
  if (speed) window.scrollBy(0, Math.max(-12,Math.min(12,speed)));
  var delta = drag.y + window.pageYOffset - drag.startY, target = groupDropTarget(drag);
  drag.target = target;
  for (var n=0;n<drag.cards.length;n++) {
    var shift = n===drag.from ? delta : n>drag.from && n<=target ? drag.rects[n-1].top-drag.rects[n].top : n<drag.from && n>=target ? drag.rects[n+1].top-drag.rects[n].top : 0;
    drag.cards[n].style.transform = 'translateY('+shift+'px)';
  }
  drag.frame = requestAnimationFrame(groupDragFrame);
}
function finishGroupDrag(event) {
  var drag = groupDrag; if (!drag || (event.pointerId !== undefined && event.pointerId !== drag.pointerId)) return;
  var touch = event.changedTouches ? event.changedTouches[0] : event;
  if (touch && typeof touch.clientY === 'number') drag.y = touch.clientY;
  drag.target=groupDropTarget(drag);endGroupDrag(false);
}
function cancelGroupDrag() { endGroupDrag(true); }
function endGroupDrag(cancel) {
  var drag=groupDrag;if(!drag)return;groupDrag=null;cancelAnimationFrame(drag.frame);
  if (drag.pointerId !== undefined && drag.handle.hasPointerCapture && drag.handle.hasPointerCapture(drag.pointerId)) drag.handle.releasePointerCapture(drag.pointerId);
  document.removeEventListener('pointermove',moveGroupDrag,false);document.removeEventListener('pointerup',finishGroupDrag,false);document.removeEventListener('pointercancel',cancelGroupDrag,false);
  document.removeEventListener('mousemove',moveGroupDrag,false);document.removeEventListener('mouseup',finishGroupDrag,false);
  document.removeEventListener('touchmove',moveGroupDrag,false);document.removeEventListener('touchend',finishGroupDrag,false);document.removeEventListener('touchcancel',cancelGroupDrag,false);
  for(var i=0;i<drag.cards.length;i++)drag.cards[i].style.transform='';
  drag.card.className=drag.card.className.replace(' group-sorting','');
  if(!cancel)movePlaylistGroup(drag.from,drag.target,'handle');
}
function savePlaylistGroupState(input) {
  var group = input._group, enabled = input.checked;
  if (groupBusy || (channelEditor && channelEditor.dirty)) {
    input.checked = !enabled; toast('请先保存或取消频道修改', true); return;
  }
  closeGroupChannels(); setGroupBusy(true);
  api('/api/settings', { playlistGroupStates: [{ id: group.id, name: group.name, enabled: enabled }] }, function (error, result) {
    setGroupBusy(false);
    if (error) { input.checked = !enabled; toast(error.message, true); return; }
    group.enabled = enabled; refresh(); toast(result.message || '频道分组设置已保存');
  });
}
function closeGroupChannels() {
  channelLoadSequence++;
  if (channelLoad) { channelLoad.abort(); channelLoad = null; }
  var editor = channelEditor;
  if (!editor) return;
  endChannelDrag(true);
  if (editor.fullscreen) setChannelFullscreen(false);
  if (editor.layoutFrame) cancelAnimationFrame(editor.layoutFrame);
  if (editor.frame) cancelAnimationFrame(editor.frame);
  editor.button.textContent = '管理频道'; editor.button.setAttribute('aria-expanded', 'false');
  editor.button._card.className = editor.button._card.className.replace(' group-editing', '');
  editor.panel.parentNode.removeChild(editor.panel); channelEditor = null;
}
function openGroupChannels(button) {
  if (groupBusy) return;
  if (channelEditor && channelEditor.dirty) { toast('请先保存或取消频道修改', true); return; }
  var same = channelEditor && channelEditor.button === button;
  closeGroupChannels(); if (same) return;
  var group = button._group, panel = groupElement('div', 'group-channel-panel'), hint = groupElement('p', 'hint channel-editor-hint', '正在读取频道…');
  button.textContent = '收起'; button.setAttribute('aria-expanded', 'true'); panel.appendChild(hint); button._card.appendChild(panel);
  button._card.className += ' group-editing';
  var editor = { group: group, button: button, panel: panel, hint: hint, channels: [], dirty: false, first: -1 };
  channelEditor = editor;
  var sequence = channelLoadSequence;
  channelLoad = api('/api/playlist/channels?group=' + encodeURIComponent(group.id), null, function (error, data) {
    if (sequence !== channelLoadSequence || channelEditor !== editor) return;
    channelLoad = null;
    if (error) { hint.textContent = error.message + '，请收起后重试。'; return; }
    editor.channels = data.channels || []; editor.revision = data.revision;
    buildChannelEditor(editor);
  });
}
function buildChannelEditor(editor) {
  var tools = groupElement('div', 'channel-editor-tools'), identity = groupElement('div', 'channel-editor-identity'),
    title = groupElement('b', 'channel-editor-title', editor.group.name || '频道管理'),
    summary = groupElement('span', 'channel-editor-summary'), full = groupElement('button', 'channel-editor-full', '全屏管理'),
    help = groupElement('button', 'channel-editor-help', '?'), cancel = groupElement('button', 'channel-editor-cancel', '取消'),
    save = groupElement('button', 'primary', '保存'), viewport = groupElement('div', 'channel-editor-viewport'),
    space = groupElement('div', 'channel-editor-space');
  editor.hint.textContent = '拖动六点排序 · 开关控制显示 · 保存后生效';
  if (editor.group.enabled === false) editor.hint.textContent += ' · 此分组已关闭';
  editor.hint.hidden = true; editor.hint.id = 'channelEditorHelp';
  title.id = 'channelEditorTitle';
  full.type = help.type = cancel.type = save.type = 'button';
  full.onclick = function () { setChannelFullscreen(!editor.fullscreen); };
  full.setAttribute('aria-label', '全屏管理频道列表'); full.setAttribute('aria-expanded', 'false');
  help.setAttribute('aria-label', '操作说明'); help.setAttribute('aria-expanded', 'false');
  help.setAttribute('aria-controls', editor.hint.id);
  help.onclick = function () {
    editor.hint.hidden = !editor.hint.hidden;
    this.setAttribute('aria-expanded', editor.hint.hidden ? 'false' : 'true');
    refreshChannelLayout();
  };
  summary.setAttribute('role', 'status'); summary.setAttribute('aria-live', 'polite');
  cancel.onclick = function () { if (!editor.saving) { closeGroupChannels(); editor.button.focus(); } };
  save.onclick = saveGroupChannels;
  identity.appendChild(title); identity.appendChild(summary);
  tools.appendChild(full); tools.appendChild(identity); tools.appendChild(help); tools.appendChild(cancel); tools.appendChild(save);
  viewport.setAttribute('aria-label', (editor.group.name || '分组') + '频道列表'); viewport.setAttribute('role', 'list'); viewport.tabIndex = 0;
  viewport.appendChild(space);
  editor.panel.insertBefore(tools, editor.hint); editor.panel.appendChild(viewport);
  editor.summary = summary; editor.save = save; editor.cancel = cancel; editor.fullButton = full;
  editor.viewport = viewport; editor.space = space;
  space.style.height = (editor.channels.length * channelRowHeight) + 'px';
  viewport.addEventListener('scroll', function () {
    if (!editor.frame) editor.frame = requestAnimationFrame(function () {
      editor.frame = null; if (channelEditor === editor) renderChannelRows(false);
    });
  }, false);
  renderChannelRows(true); updateChannelEditor();
}
function updateChannelEditor() {
  var editor = channelEditor, hidden = 0;
  for (var i = 0; i < editor.channels.length; i++) if (editor.channels[i].enabled === false) hidden++;
  editor.summary.textContent = editor.channels.length + ' 个 · 隐藏 ' + hidden + (editor.dirty ? ' · 未保存' : '');
  editor.save.disabled = (!editor.dirty && !editor.needsReload) || editor.saving;
  editor.cancel.disabled = !!editor.saving;
  editor.save.textContent = editor.saving ? '保存中' : editor.needsReload ? '重试同步' : '保存';
}
function refreshChannelLayout() {
  var editor = channelEditor;
  if (!editor || !editor.viewport) return;
  endChannelDrag(true);
  if (editor.layoutFrame) cancelAnimationFrame(editor.layoutFrame);
  editor.layoutFrame = requestAnimationFrame(function () {
    editor.layoutFrame = null;
    if (channelEditor === editor) renderChannelRows(true);
  });
}
function setChannelFullscreen(fullscreen) {
  var editor = channelEditor;
  if (!editor || !editor.viewport || !!editor.fullscreen === fullscreen) return;
  endChannelDrag(true);
  var scroll = editor.viewport.scrollTop, main = document.getElementById('groupManagerPage');
  editor.fullscreen = fullscreen;
  if (fullscreen) {
    editor.pageScroll = window.pageYOffset || 0;
    editor.mainAriaHidden = main.getAttribute('aria-hidden');
    main.setAttribute('aria-hidden', 'true');
    document.body.className += ' channel-editor-open';
    document.body.appendChild(editor.panel);
    editor.panel.className = 'group-channel-panel channel-editor-fullscreen';
    editor.panel.tabIndex = -1;
    editor.panel.setAttribute('role', 'dialog'); editor.panel.setAttribute('aria-modal', 'true');
    editor.panel.setAttribute('aria-labelledby', 'channelEditorTitle');
    editor.fullButton.textContent = '‹'; editor.fullButton.setAttribute('aria-label', '退出全屏管理');
    if (window.NtvNavigation) NtvNavigation.overlayOpen('channel-editor-fullscreen', function () {
      setChannelFullscreen(false); return true;
    });
  } else {
    editor.button._card.appendChild(editor.panel);
    editor.panel.className = 'group-channel-panel';
    editor.panel.removeAttribute('role'); editor.panel.removeAttribute('aria-modal'); editor.panel.removeAttribute('aria-labelledby');
    editor.panel.removeAttribute('tabindex');
    document.body.className = document.body.className.replace(' channel-editor-open', '');
    if (editor.mainAriaHidden === null) main.removeAttribute('aria-hidden');
    else main.setAttribute('aria-hidden', editor.mainAriaHidden);
    editor.fullButton.textContent = '全屏管理'; editor.fullButton.setAttribute('aria-label', '全屏管理频道列表');
    window.scrollTo(0, editor.pageScroll || 0);
    if (window.NtvNavigation) NtvNavigation.overlayClosed('channel-editor-fullscreen');
  }
  editor.fullButton.setAttribute('aria-expanded', fullscreen ? 'true' : 'false');
  editor.viewport.scrollTop = scroll;
  renderChannelRows(true); refreshChannelLayout();
  var focusTarget = fullscreen ? editor.panel : editor.fullButton;
  try { focusTarget.focus({ preventScroll: true }); } catch (ignored) { focusTarget.focus(); }
}
function channelEditorKeydown(event) {
  var editor = channelEditor;
  if (!editor || !editor.fullscreen) return;
  if (event.key === 'Escape') { event.preventDefault(); setChannelFullscreen(false); return; }
  if (event.key !== 'Tab') return;
  var controls = editor.panel.querySelectorAll('button:not([disabled]), input:not([disabled]), [tabindex="0"]');
  var first = controls[0], last = controls[controls.length - 1], active = document.activeElement;
  if (event.shiftKey && (active === first || active === editor.panel || !editor.panel.contains(active))) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && (active === last || !editor.panel.contains(active))) { event.preventDefault(); first.focus(); }
}
function createManagedChannelRow(editor) {
  var row = groupElement('div', 'managed-channel-row'), handle = groupElement('button', 'channel-drag-handle'),
    copy = groupElement('div', 'managed-channel-copy'), controls = groupElement('div', 'channel-order-controls'),
    up = groupElement('button'), down = groupElement('button'), enabled = document.createElement('input');
  handle.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="8" cy="5" r="1.6"/><circle cx="16" cy="5" r="1.6"/><circle cx="8" cy="12" r="1.6"/><circle cx="16" cy="12" r="1.6"/><circle cx="8" cy="19" r="1.6"/><circle cx="16" cy="19" r="1.6"/></svg>';
  up.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m6 14 6-6 6 6"/></svg>';
  down.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m6 10 6 6 6-6"/></svg>';
  row.setAttribute('role', 'listitem'); handle.type = up.type = down.type = 'button';
  handle.title = '拖动排序，或用方向键移动';

  handle.onkeydown = function (event) {
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault(); moveGroupChannel(this._index, this._index + (event.key === 'ArrowUp' ? -1 : 1), 'handle');
    }
  };
  copy.appendChild(groupElement('b')); copy.appendChild(groupElement('span'));
  up.onclick = function () { moveGroupChannel(this._index, this._index - 1, 'up'); };
  down.onclick = function () { moveGroupChannel(this._index, this._index + 1, 'down'); };
  controls.appendChild(up); controls.appendChild(down);
  enabled.type = 'checkbox'; enabled.className = 'source-toggle';
  enabled.onchange = function () {
    editor.channels[this._index].enabled = this.checked; editor.dirty = true; var index = this._index;
    renderChannelRows(true); updateChannelEditor(); focusChannelControl(index, 'toggle');
  };
  row.appendChild(handle); row.appendChild(copy); row.appendChild(controls); row.appendChild(enabled);
  return row;
}
function bindManagedChannelRow(row, index, editor) {
  var channel = editor.channels[index], handle = row.children[0], copy = row.children[1],
    up = row.children[2].children[0], down = row.children[2].children[1], enabled = row.children[3];
  row._index = handle._index = up._index = down._index = enabled._index = index;
  row.style.top = (index * channelRowHeight) + 'px';
  row.setAttribute('aria-posinset', String(index + 1)); row.setAttribute('aria-setsize', String(editor.channels.length));
  handle.setAttribute('aria-label', '拖动排序 ' + channel.name);
  handle.onpointerdown = window.PointerEvent ? beginChannelDrag : null;
  handle.onmousedown = window.PointerEvent ? null : beginChannelDrag;
  handle.ontouchstart = window.PointerEvent ? null : beginChannelDrag;
  copy.children[0].textContent = channel.name;
  copy.children[1].textContent = (index + 1) + ' · ' + (channel.enabled === false ? '已隐藏' : '显示中');
  up.disabled = index === 0 || editor.saving || editor.needsReload; down.disabled = index === editor.channels.length - 1 || editor.saving || editor.needsReload;
  up.setAttribute('aria-label', '上移 ' + channel.name); down.setAttribute('aria-label', '下移 ' + channel.name);
  enabled.checked = channel.enabled !== false; enabled.disabled = handle.disabled = !!(editor.saving || editor.needsReload);
  enabled.setAttribute('aria-label', '显示 ' + channel.name);
}
function renderChannelRows(force) {
  var editor = channelEditor;
  if (!editor || !editor.viewport) return;
  var first = Math.max(0, Math.floor(editor.viewport.scrollTop / channelRowHeight) - 3),
    end = Math.min(editor.channels.length, first + Math.ceil((editor.viewport.clientHeight || 420) / channelRowHeight) + 6);
  if (!force && first === editor.first) return;
  editor.first = first;
  var existing = {}, spare = [], nodes = editor.space.children, i;
  for (i = 0; i < nodes.length; i++) {
    var node = nodes[i];
    if (node._index >= first && node._index < end) existing[node._index] = node;
    else spare.push(node);
  }
  for (i = 0; i < spare.length; i++) editor.space.removeChild(spare[i]);
  // Rebind only rows entering the viewport. Keep visible rows and their SVGs,
  // focus and event handlers intact instead of rebuilding the window on scroll.
  for (i = first; i < end; i++) {
    var row = existing[i] || spare.pop() || createManagedChannelRow(editor);
    if (force || row._index !== i) bindManagedChannelRow(row, i, editor);
    var position = i - first;
    if (editor.space.children[position] !== row)
      editor.space.insertBefore(row, editor.space.children[position] || null);
  }
  markChannelDropTarget();
}
function focusChannelControl(index, control) {
  var rows = channelEditor.space.children;
  for (var i = 0; i < rows.length; i++) if (rows[i]._index === index) {
    var row = rows[i], el = control === 'toggle' ? row.children[3] : control === 'handle' ? row.children[0] : row.children[2].children[control === 'up' ? 0 : 1];
    if (el.disabled) el = row.children[0];
    try { el.focus({ preventScroll: true }); } catch (ignored) { el.focus(); } return;
  }
}
function moveGroupChannel(from, target, control) {
  var editor = channelEditor;
  if (!editor || editor.saving || editor.needsReload || target < 0 || target >= editor.channels.length || from === target) return;
  editor.channels.splice(target, 0, editor.channels.splice(from, 1)[0]); editor.dirty = true;
  var top = target * channelRowHeight, viewport = editor.viewport;
  if (top < viewport.scrollTop) viewport.scrollTop = top;
  else if (top + channelRowHeight > viewport.scrollTop + viewport.clientHeight) viewport.scrollTop = top + channelRowHeight - viewport.clientHeight;
  renderChannelRows(true); updateChannelEditor(); focusChannelControl(target, control || 'handle');
}
function saveGroupChannels() {
  var editor = channelEditor;
  if (!editor || editor.saving) return;
  if (editor.needsReload) { reloadSavedGroupChannels(editor); return; }
  if (!editor.dirty) return;
  endChannelDrag(true);
  var order = [], hidden = [];
  for (var i = 0; i < editor.channels.length; i++) { order.push(editor.channels[i].id); if (editor.channels[i].enabled === false) hidden.push(editor.channels[i].id); }
  editor.saving = true; setGroupBusy(true); updateChannelEditor(); renderChannelRows(true);
  api('/api/settings', { playlistChannelSettings: { groupId: editor.group.id, revision: editor.revision, order: order, hidden: hidden } }, function (error, result) {
    if (error) {
      editor.saving = false; setGroupBusy(false);
      updateChannelEditor(); renderChannelRows(true); toast(error.message, true); return;
    }
    editor.dirty = false; reloadSavedGroupChannels(editor); toast(result.message || '频道设置已保存');
  });
}
function reloadSavedGroupChannels(editor) {
  editor.saving = true; setGroupBusy(true); updateChannelEditor(); renderChannelRows(true);
  api('/api/playlist/channels?group=' + encodeURIComponent(editor.group.id), null, function (error, data) {
    if (channelEditor !== editor) return;
    editor.saving = false; editor.needsReload = !!error; setGroupBusy(false);
    if (error) toast('已保存，读取最新状态失败，请点重试同步', true);
    else {
      editor.channels = data.channels || []; editor.revision = data.revision;
      editor.space.style.height = (editor.channels.length * channelRowHeight) + 'px';
    }
    updateChannelEditor(); renderChannelRows(true);
  });
}
function beginChannelDrag(event) {
  var editor = channelEditor;
  if (!editor || editor.saving || editor.needsReload || editor.drag || (event.button !== undefined && event.button !== 0) || (event.touches && event.touches.length !== 1) || event.isPrimary === false) return;
  event.preventDefault(); var point = event.touches ? event.touches[0] : event;
  editor.drag = { from: this._index, target: this._index, y: point.clientY, pointerId: event.pointerId, touchId: point.identifier, handle: this,
    bounds: editor.viewport.getBoundingClientRect(), last: 0 };
  // Capture on the stable viewport, since virtual rows are recycled while scrolling.
  if (event.pointerId !== undefined && editor.viewport.setPointerCapture)
    editor.viewport.setPointerCapture(event.pointerId);
  editor.viewport.className += ' channel-dragging';
  if (event.pointerId !== undefined) {
    document.addEventListener('pointermove', moveChannelDrag, false); document.addEventListener('pointerup', finishChannelDrag, false);
    document.addEventListener('pointercancel', cancelChannelDrag, false);
  } else if (event.touches) {
    // A touch keeps its original target even if that virtual row leaves the DOM.
    this.addEventListener('touchmove', moveChannelDrag, false);
    this.addEventListener('touchend', finishChannelDrag, false);
    this.addEventListener('touchcancel', cancelChannelDrag, false);
  } else {
    document.addEventListener('mousemove', moveChannelDrag, false); document.addEventListener('mouseup', finishChannelDrag, false);
  }
  editor.drag.frame = requestAnimationFrame(tickChannelDrag);
}
function moveChannelDrag(event) {
  var drag = channelEditor && channelEditor.drag;
  if (!drag || (event.pointerId !== undefined && event.pointerId !== drag.pointerId)) return;
  var point = event.touches ? event.touches[0] : event;
  if (event.touches && (event.touches.length !== 1 || point.identifier !== drag.touchId)) { endChannelDrag(true); return; }
  if (event.cancelable) event.preventDefault(); drag.y = point.clientY;
}
function tickChannelDrag(time) {
  var editor = channelEditor, drag = editor && editor.drag;
  if (!drag) return;
  var bounds = drag.bounds, elapsed = drag.last ? Math.min(32, time - drag.last) : 16, edge = Math.min(56, (bounds.bottom - bounds.top) / 4), speed = 0;
  drag.last = time;
  if (drag.y < bounds.top + edge) speed = -Math.min(1, (bounds.top + edge - drag.y) / edge);
  else if (drag.y > bounds.bottom - edge) speed = Math.min(1, (drag.y - bounds.bottom + edge) / edge);
  if (speed) editor.viewport.scrollTop += speed * elapsed * 0.65;
  drag.target = Math.max(0, Math.min(editor.channels.length - 1, Math.floor((editor.viewport.scrollTop + Math.max(0, Math.min(bounds.bottom - bounds.top - 1, drag.y - bounds.top))) / channelRowHeight)));
  renderChannelRows(false); markChannelDropTarget(); drag.frame = requestAnimationFrame(tickChannelDrag);
}
function markChannelDropTarget() {
  var editor = channelEditor, drag = editor && editor.drag;
  if (!editor || !editor.space) return;
  var rows = editor.space.children;
  for (var i = 0; i < rows.length; i++) {
    var row = rows[i], channel = editor.channels[row._index], className = 'managed-channel-row' + (channel.enabled === false ? ' channel-hidden' : '') + (drag && row._index === drag.target ? ' channel-drop-target' : '');
    if (row.className !== className) row.className = className;
  }
  if (drag && drag.announced !== drag.target) { drag.announced = drag.target; editor.summary.textContent = '松开移至第 ' + (drag.target + 1) + ' 位'; }
}
function finishChannelDrag(event) {
  var drag = channelEditor && channelEditor.drag;
  if (drag && (event.pointerId === undefined || event.pointerId === drag.pointerId)) endChannelDrag(false);
}
function cancelChannelDrag() { endChannelDrag(true); }
function endChannelDrag(cancel) {
  var editor = channelEditor, drag = editor && editor.drag;
  if (!drag) return;
  cancelAnimationFrame(drag.frame); editor.drag = null;
  drag.handle.removeEventListener('touchmove', moveChannelDrag, false);
  drag.handle.removeEventListener('touchend', finishChannelDrag, false);
  drag.handle.removeEventListener('touchcancel', cancelChannelDrag, false);
  if (drag.pointerId !== undefined && editor.viewport.hasPointerCapture && editor.viewport.hasPointerCapture(drag.pointerId))
    editor.viewport.releasePointerCapture(drag.pointerId);
  document.removeEventListener('pointermove', moveChannelDrag, false); document.removeEventListener('pointerup', finishChannelDrag, false);
  document.removeEventListener('pointercancel', cancelChannelDrag, false); document.removeEventListener('mousemove', moveChannelDrag, false);
  document.removeEventListener('mouseup', finishChannelDrag, false); document.removeEventListener('touchmove', moveChannelDrag, false);
  document.removeEventListener('touchend', finishChannelDrag, false); document.removeEventListener('touchcancel', cancelChannelDrag, false);
  editor.viewport.className = 'channel-editor-viewport';
  if (!cancel) moveGroupChannel(drag.from, drag.target);
  markChannelDropTarget(); updateChannelEditor();
}
function renderPageState() {
  // Keep even a clean inline editor alive while state polling catches up after a save.
  // The latest group snapshot is applied on the first poll after the editor closes.
  if (groupBusy || groupDrag || channelEditor) return;
  var groups = state.settings.playlistGroups || [], signature = JSON.stringify(groups);
  if (signature === groupSignature) return;
  closeGroupChannels(); groupSignature = signature; playlistGroups = groups; renderPlaylistGroupSettings();
}
window.addEventListener('keydown', channelEditorKeydown, false);
window.addEventListener('resize', refreshChannelLayout, false);
window.addEventListener('blur', cancelChannelDrag, false);
window.addEventListener('blur', cancelGroupDrag, false);
window.addEventListener('pagehide', cancelGroupDrag, false);
window.addEventListener('pagehide', cancelChannelDrag, false);
window.addEventListener('beforeunload', function (event) {
  if (channelEditor && (channelEditor.dirty || channelEditor.saving)) { event.preventDefault(); event.returnValue = '频道修改尚未保存'; return event.returnValue; }
}, false);
document.addEventListener('visibilitychange', function () { if (isPageHidden()) { cancelChannelDrag(); cancelGroupDrag(); } }, false);
startPage();
