package app.browseragent.browser

/** Builds a compact JSON snapshot of the visible page and its interactive elements. */
val SNAPSHOT_JS: String = """
(function () {
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=switch],[role=combobox],[role=option],[contenteditable]:not([contenteditable=false]),[onclick]';
  var MAX = 200;
  function clean(t, max) {
    t = String(t == null ? '' : t).replace(/\s+/g, ' ').trim();
    return t.length > max ? t.slice(0, max) + '...' : t;
  }
  function win(el) { return el.ownerDocument.defaultView || window; }
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    var s = win(el).getComputedStyle(el);
    return !(s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0);
  }
  function labelOf(el) {
    var a = el.getAttribute('aria-label');
    if (a) return a;
    if (el.labels && el.labels.length) return el.labels[0].innerText;
    var lb = el.getAttribute('aria-labelledby');
    if (lb) {
      var ref = el.ownerDocument.getElementById(lb.split(' ')[0]);
      if (ref) return ref.innerText;
    }
    return el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('name') || '';
  }
  function collect(root, acc) {
    var olds = root.querySelectorAll('[data-agent-id]');
    for (var k = 0; k < olds.length; k++) olds[k].removeAttribute('data-agent-id');
    var els = root.querySelectorAll(SEL);
    for (var i = 0; i < els.length; i++) acc.push(els[i]);
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      var e = all[j];
      if (e.shadowRoot) collect(e.shadowRoot, acc);
      if (e.tagName === 'IFRAME') {
        try { if (e.contentDocument) collect(e.contentDocument, acc); } catch (x) {}
      }
    }
  }
  var acc = [];
  collect(document, acc);
  var items = [];
  var truncated = false;
  for (var n = 0; n < acc.length; n++) {
    var el = acc[n];
    if (!visible(el)) continue;
    if (items.length >= MAX) { truncated = true; break; }
    var id = 'e' + (items.length + 1);
    el.setAttribute('data-agent-id', id);
    var tag = el.tagName.toLowerCase();
    var type = (el.getAttribute('type') || '').toLowerCase();
    var role = el.getAttribute('role') || (tag === 'input' ? 'input:' + (type || 'text') : tag);
    var item = { id: id, role: role, label: clean(labelOf(el) || el.innerText || el.value, 80) };
    if (tag === 'input' && type === 'password') {
      item.value = el.value ? '(set)' : '';
    } else if (tag === 'input' || tag === 'textarea') {
      if (type !== 'checkbox' && type !== 'radio' && type !== 'submit' && type !== 'button') item.value = clean(el.value, 80);
    }
    if (tag === 'input' && (type === 'checkbox' || type === 'radio')) item.checked = !!el.checked;
    else if (el.getAttribute('aria-checked')) item.checked = el.getAttribute('aria-checked') === 'true';
    if (tag === 'select') {
      var opts = [];
      for (var o = 0; o < el.options.length && o < 20; o++) opts.push(clean(el.options[o].text, 40));
      item.options = opts;
      item.value = el.selectedIndex >= 0 ? clean(el.options[el.selectedIndex].text, 40) : '';
    }
    if (tag === 'a') item.href = clean(el.getAttribute('href'), 80);
    if (el.disabled) item.disabled = true;
    var rect = el.getBoundingClientRect();
    if (rect.bottom < 0 || rect.top > (win(el).innerHeight || 0)) item.off = true;
    items.push(item);
  }
  var body = document.body;
  return JSON.stringify({
    url: location.href,
    title: document.title,
    scrollY: Math.round(window.scrollY),
    scrollMax: Math.max(0, Math.round(document.documentElement.scrollHeight - window.innerHeight)),
    text: clean(body ? body.innerText : '', 3500),
    elements: items,
    truncated: truncated
  });
})()
"""

/** Body of an action function: (function (op, id, arg) { ... }) */
val ACT_JS: String = """
function find(id, root) {
  var el = root.querySelector('[data-agent-id=' + id + ']');
  if (el) return el;
  var all = root.querySelectorAll('*');
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (e.shadowRoot) { var f = find(id, e.shadowRoot); if (f) return f; }
    if (e.tagName === 'IFRAME') {
      try { if (e.contentDocument) { var g = find(id, e.contentDocument); if (g) return g; } } catch (x) {}
    }
  }
  return null;
}
function res(ok, msg) { return JSON.stringify({ ok: ok, msg: msg }); }
try {
  if (op === 'scroll') {
    var h = window.innerHeight * 0.8;
    if (arg === 'up') window.scrollBy(0, -h);
    else if (arg === 'top') window.scrollTo(0, 0);
    else if (arg === 'bottom') window.scrollTo(0, document.documentElement.scrollHeight);
    else window.scrollBy(0, h);
    return res(true, 'scrolled ' + arg);
  }
  var el = find(id, document);
  if (!el) return res(false, 'Element ' + id + ' not found. Ids change after the page updates; use ids from the latest snapshot.');
  el.scrollIntoView({ block: 'center', inline: 'center' });
  var w = el.ownerDocument.defaultView || window;
  var tag = el.tagName;
  if (op === 'click') { el.click(); return res(true, 'clicked ' + id); }
  if (op === 'fill') {
    el.focus();
    if (tag === 'INPUT' || tag === 'TEXTAREA') {
      var proto = tag === 'TEXTAREA' ? w.HTMLTextAreaElement.prototype : w.HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, arg);
      el.dispatchEvent(new w.Event('input', { bubbles: true }));
      el.dispatchEvent(new w.Event('change', { bubbles: true }));
    } else if (el.isContentEditable) {
      el.textContent = arg;
      el.dispatchEvent(new w.Event('input', { bubbles: true }));
    } else {
      return res(false, id + ' is not a text field');
    }
    return res(true, 'filled ' + id);
  }
  if (op === 'select') {
    if (tag !== 'SELECT') return res(false, id + ' is not a select');
    var want = String(arg).toLowerCase();
    var idx = -1;
    for (var i = 0; i < el.options.length; i++) {
      var op1 = el.options[i];
      if (op1.text.trim().toLowerCase() === want || op1.value.toLowerCase() === want) { idx = i; break; }
    }
    if (idx < 0) {
      for (var j = 0; j < el.options.length; j++) {
        if (el.options[j].text.toLowerCase().indexOf(want) >= 0) { idx = j; break; }
      }
    }
    if (idx < 0) return res(false, 'No option matching ' + arg);
    el.selectedIndex = idx;
    el.dispatchEvent(new w.Event('input', { bubbles: true }));
    el.dispatchEvent(new w.Event('change', { bubbles: true }));
    return res(true, 'selected ' + el.options[idx].text);
  }
  if (op === 'enter') {
    var init = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    el.focus();
    var notPrevented = el.dispatchEvent(new w.KeyboardEvent('keydown', init));
    el.dispatchEvent(new w.KeyboardEvent('keypress', init));
    el.dispatchEvent(new w.KeyboardEvent('keyup', init));
    if (notPrevented && el.form) {
      if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
    }
    return res(true, 'pressed Enter on ' + id);
  }
  return res(false, 'unknown op ' + op);
} catch (err) {
  return res(false, String(err && err.message ? err.message : err));
}
"""
