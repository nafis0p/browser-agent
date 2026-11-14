(function () {
  if (window.__agentBridge) return;
  window.__agentBridge = true;

  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=switch],[role=combobox],[role=option],[contenteditable]:not([contenteditable=false]),[onclick]';
  var MAX = 200;
  var isTop = window === window.top;
  var port = browser.runtime.connectNative('browser');
  var acting = false; // true while the agent itself is acting (its events are not "user" events)

  // Fields that hold personal data: their values are never sent to the AI.
  var SENS = new RegExp('pass|pwd|pin|otp|cvv|cvc|card|cc-|iban|account|ssn|social|e-?mail|phone|tel|mobile|address|street|zip|postal|billing|birth|dob|user|login|first-?name|last-?name|full-?name|surname', 'i');

  function sensitive(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    if (type === 'password' || type === 'email' || type === 'tel') return true;
    var hay = [el.getAttribute('autocomplete'), el.getAttribute('name'), el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label')].join(' ');
    return SENS.test(hay);
  }

  function clean(t, max) {
    t = String(t == null ? '' : t).replace(/\s+/g, ' ').trim();
    return t.length > max ? t.slice(0, max) + '...' : t;
  }

  // Content scripts can see closed shadow roots in Firefox.
  function shadowOf(el) {
    return el.openOrClosedShadowRoot || el.shadowRoot || null;
  }

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    var s = window.getComputedStyle(el);
    return !(s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0);
  }

  function labelOf(el) {
    var a = el.getAttribute('aria-label');
    if (a) return a;
    if (el.labels && el.labels.length) return el.labels[0].innerText;
    var lb = el.getAttribute('aria-labelledby');
    if (lb) {
      var ref = document.getElementById(lb.split(' ')[0]);
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
      var sr = shadowOf(all[j]);
      if (sr) collect(sr, acc);
    }
  }

  // prefix is '' for the top frame and 'f1', 'f2', ... for sub-frames, so ids are unique.
  function snapshot(prefix) {
    var acc = [];
    collect(document, acc);
    var items = [];
    var truncated = false;
    for (var n = 0; n < acc.length; n++) {
      var el = acc[n];
      if (!visible(el)) continue;
      if (items.length >= MAX) { truncated = true; break; }
      var id = prefix + 'e' + (items.length + 1);
      el.setAttribute('data-agent-id', id);
      var tag = el.tagName.toLowerCase();
      var type = (el.getAttribute('type') || '').toLowerCase();
      var role = el.getAttribute('role') || (tag === 'input' ? 'input:' + (type || 'text') : tag);
      var item = { id: id, role: role, label: clean(labelOf(el) || el.innerText || el.value, 80) };
      if (tag === 'input' || tag === 'textarea') {
        if (type !== 'checkbox' && type !== 'radio' && type !== 'submit' && type !== 'button') {
          item.value = sensitive(el) ? (el.value ? '(set)' : '') : clean(el.value, 80);
        }
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
      if (isTop) {
        var rect = el.getBoundingClientRect();
        if (rect.bottom < 0 || rect.top > (window.innerHeight || 0)) item.off = true;
      }
      items.push(item);
    }
    var out = { url: location.href, elements: items, truncated: truncated };
    if (isTop) {
      var body = document.body;
      out.title = document.title;
      out.scrollY = Math.round(window.scrollY);
      out.scrollMax = Math.max(0, Math.round(document.documentElement.scrollHeight - window.innerHeight));
      out.text = clean(body ? body.innerText : '', 3500);
    }
    return out;
  }

  function find(id, root) {
    var el = root.querySelector('[data-agent-id=' + id + ']');
    if (el) return el;
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var sr = shadowOf(all[i]);
      if (sr) {
        var f = find(id, sr);
        if (f) return f;
      }
    }
    return null;
  }

  function res(ok, msg) { return { ok: ok, msg: msg }; }

  function act(op, id, arg) {
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
    var tag = el.tagName;

    if (op === 'click') {
      var a = el.closest ? el.closest('a') : null;
      if (a && a.target === '_blank') a.target = '_self';
      el.click();
      return res(true, 'clicked ' + id);
    }

    if (op === 'fill') {
      el.focus();
      if (tag === 'INPUT' || tag === 'TEXTAREA') {
        el.value = arg;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } else if (el.isContentEditable) {
        el.textContent = arg;
        el.dispatchEvent(new Event('input', { bubbles: true }));
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
        var opt = el.options[i];
        if (opt.text.trim().toLowerCase() === want || opt.value.toLowerCase() === want) { idx = i; break; }
      }
      if (idx < 0) {
        for (var j = 0; j < el.options.length; j++) {
          if (el.options[j].text.toLowerCase().indexOf(want) >= 0) { idx = j; break; }
        }
      }
      if (idx < 0) return res(false, 'No option matching ' + arg);
      el.selectedIndex = idx;
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return res(true, 'selected ' + el.options[idx].text);
    }

    if (op === 'enter') {
      var init = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
      el.focus();
      var notPrevented = el.dispatchEvent(new KeyboardEvent('keydown', init));
      el.dispatchEvent(new KeyboardEvent('keypress', init));
      el.dispatchEvent(new KeyboardEvent('keyup', init));
      if (notPrevented && el.form) {
        if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
      }
      return res(true, 'pressed Enter on ' + id);
    }

    return res(false, 'unknown op ' + op);
  }

  port.onMessage.addListener(function (msg) {
    var data;
    try {
      if (msg.op === 'snapshot') {
        data = snapshot(msg.prefix || '');
      } else {
        acting = true;
        try {
          data = act(msg.op, msg.id, msg.arg);
        } finally {
          acting = false;
        }
      }
    } catch (e) {
      data = { ok: false, msg: String(e && e.message ? e.message : e) };
    }
    port.postMessage({ type: 'reply', rid: msg.rid, data: data });
  });

  port.postMessage({ type: 'hello', top: isTop, url: location.href });

  // ---- Tell the app which page is open (for the browsing history).
  function sendPage() {
    if (!isTop) return;
    port.postMessage({ type: 'page', url: location.href, title: document.title });
  }
  document.addEventListener('DOMContentLoaded', sendPage);
  window.addEventListener('load', function () {
    sendPage();
    setTimeout(sendPage, 1500);
  });

  // ---- Report what the USER does by hand, so the agent never works from a stale picture.
  var lastKey = '';
  var lastAt = 0;

  function describe(el) {
    var t = el;
    if (el && el.closest) {
      t = el.closest('a,button,input,select,textarea,summary,label,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem]') || el;
    }
    return clean(labelOf(t) || t.innerText || t.value || t.tagName, 60);
  }

  function report(kind, el, value) {
    if (acting || !el || el.nodeType !== 1) return;
    var label = describe(el);
    var key = kind + '|' + label + '|' + value;
    var now = Date.now();
    if (key === lastKey && now - lastAt < 800) return;
    lastKey = key;
    lastAt = now;
    port.postMessage({ type: 'user', kind: kind, label: label, value: value || '', url: location.href });
  }

  // Only real user events are trusted; events created by scripts are not.
  document.addEventListener('click', function (e) {
    if (e.isTrusted) report('click', e.target, '');
  }, true);
  document.addEventListener('change', function (e) {
    if (!e.isTrusted) return;
    var el = e.target;
    // Personal data typed by the user is never reported with its value.
    if (el.getAttribute && sensitive(el)) report('changed', el, '(value hidden)');
    else report('changed', el, clean(el.value, 60));
  }, true);
  document.addEventListener('submit', function (e) {
    if (e.isTrusted) report('submit', e.target, '');
  }, true);

  // Links that would open a new window load in the same tab instead.
  document.addEventListener('click', function (e) {
    var t = e.target;
    var a = t && t.closest ? t.closest('a[target=_blank]') : null;
    if (a) a.target = '_self';
  }, true);
})();
