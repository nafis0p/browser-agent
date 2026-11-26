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

  // ---------------------------------------------------------------- page zoom (per site)
  // The zoom of a site is kept in the add-on's storage and put on the page when it opens. It is
  // done with the CSS zoom of the root element, so text and layout both follow it.

  var zoomKey = 'zoom:' + location.hostname;
  var currentZoom = 1;

  function applyZoom(z) {
    currentZoom = z;
    var root = document.documentElement;
    if (!root) return;
    if (z && z !== 1) root.style.setProperty('zoom', String(z), 'important');
    else root.style.removeProperty('zoom');
  }

  if (isTop && location.hostname) {
    browser.storage.local.get(zoomKey).then(function (stored) {
      var z = stored ? stored[zoomKey] : undefined;
      if (typeof z === 'number' && z > 0) {
        applyZoom(z);
        port.postMessage({ type: 'zoom', value: z });
      }
    }, function () {});
    // A page may rewrite the style of the root element; the zoom is put back once it is ready.
    document.addEventListener('DOMContentLoaded', function () {
      if (currentZoom !== 1) applyZoom(currentZoom);
    });
  }

  // ---------------------------------------------------------------- offline copy of the page
  // The rendered page is copied, its style sheets, images and fonts are loaded (by the background
  // page of this add-on) and put into the copy as data, scripts are removed. The result is one
  // HTML file that opens without a connection. It is sent to the app in pieces.

  var MIME_BY_EXT = {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp',
    svg: 'image/svg+xml', ico: 'image/x-icon', avif: 'image/avif', woff2: 'font/woff2', woff: 'font/woff',
    ttf: 'font/ttf', otf: 'font/otf'
  };

  function guessType(u) {
    var m = /\.([a-z0-9]+)(?:[?#]|$)/i.exec(u);
    return m ? MIME_BY_EXT[m[1].toLowerCase()] : undefined;
  }

  function fixType(d, u) {
    if (!d) return d;
    var t = guessType(u);
    if (t && /^data:(application\/octet-stream)?;/.test(d.slice(0, 40))) {
      return 'data:' + t + d.slice(d.indexOf(';'));
    }
    return d;
  }

  async function capturePage() {
    var base = document.baseURI;
    var budget = 25 * 1024 * 1024;
    var count = 0;
    var skipped = 0;
    var cache = new Map();
    var active = 0;
    var waiting = [];

    function slot() {
      return new Promise(function (resolve) {
        if (active < 6) { active++; resolve(); } else { waiting.push(resolve); }
      });
    }
    function release() {
      active--;
      var next = waiting.shift();
      if (next) { active++; next(); }
    }

    function absolute(u, b) {
      try { return new URL(u, b || base).href; } catch (e) { return null; }
    }

    async function fetchRes(url, as, max) {
      await slot();
      try {
        return await browser.runtime.sendMessage({ op: 'fetch', url: url, as: as, max: max });
      } catch (e) {
        return null;
      } finally {
        release();
      }
    }

    function dataUrl(url, max) {
      if (!url) return Promise.resolve(null);
      if (url.indexOf('data:') === 0) return Promise.resolve(url);
      if (!/^https?:/i.test(url)) return Promise.resolve(null);
      var key = 'd' + url;
      if (cache.has(key)) return cache.get(key);
      var p = (async function () {
        if (budget <= 0 || count > 600) { skipped++; return null; }
        count++;
        var r = await fetchRes(url, 'data', max);
        if (!r || !r.ok) { skipped++; return null; }
        budget -= r.size || 0;
        return fixType(r.data, url);
      })();
      cache.set(key, p);
      return p;
    }

    function textOf(url) {
      if (!url || !/^https?:/i.test(url)) return Promise.resolve('');
      var key = 't' + url;
      if (cache.has(key)) return cache.get(key);
      var p = (async function () {
        var r = await fetchRes(url, 'text', 2000000);
        if (!r || !r.ok) { skipped++; return ''; }
        return r.text || '';
      })();
      cache.set(key, p);
      return p;
    }

    // Puts the files that a style sheet uses into it (imported sheets, fonts, background images).
    async function processCss(css, cssBase, depth) {
      if (!css) return '';
      var importRe = /@import\s+(?:url\(\s*)?(['"]?)([^'")\s;]+)\1\s*\)?\s*([^;]*);/gi;
      var imports = [];
      var m;
      while ((m = importRe.exec(css)) !== null) {
        imports.push({ start: m.index, end: importRe.lastIndex, url: absolute(m[2], cssBase), media: (m[3] || '').trim() });
      }
      var out = '';
      var pos = 0;
      for (var i = 0; i < imports.length; i++) {
        var imp = imports[i];
        out += css.slice(pos, imp.start);
        pos = imp.end;
        if (depth < 3 && imp.url) {
          var t = await textOf(imp.url);
          if (t) {
            var inner = await processCss(t, imp.url, depth + 1);
            out += imp.media ? '@media ' + imp.media + ' {' + inner + '}' : inner;
          }
        }
      }
      out += css.slice(pos);

      var urlRe = /url\(\s*(['"]?)([^'")]+)\1\s*\)/gi;
      var found = [];
      while ((m = urlRe.exec(out)) !== null) {
        found.push({ start: m.index, end: urlRe.lastIndex, raw: m[2].trim() });
      }
      var results = await Promise.all(found.map(function (f) {
        if (f.raw.indexOf('data:') === 0 || f.raw.charAt(0) === '#') return Promise.resolve(null);
        var a = absolute(f.raw, cssBase);
        return a ? dataUrl(a, 1500000) : Promise.resolve(null);
      }));
      var res2 = '';
      var p2 = 0;
      for (var j = 0; j < found.length; j++) {
        res2 += out.slice(p2, found[j].start);
        var raw = found[j].raw;
        if (results[j]) {
          res2 += 'url("' + results[j] + '")';
        } else if (raw.indexOf('data:') === 0 || raw.charAt(0) === '#') {
          res2 += out.slice(found[j].start, found[j].end);
        } else {
          var a2 = absolute(raw, cssBase);
          res2 += 'url("' + (a2 || raw) + '")';
        }
        p2 = found[j].end;
      }
      res2 += out.slice(p2);
      return res2;
    }

    function rulesText(sheet) {
      try {
        if (sheet && sheet.cssRules && sheet.cssRules.length) {
          return Array.prototype.map.call(sheet.cssRules, function (r) { return r.cssText; }).join('\n');
        }
      } catch (e) {}
      return '';
    }

    // Lists of the original and the copied elements, taken before anything is removed, so that
    // the n-th element of one is the n-th element of the other.
    var clone = document.documentElement.cloneNode(true);
    // The zoom of the site is not part of the page itself.
    clone.style.removeProperty('zoom');
    function all(sel) { return Array.prototype.slice.call(clone.querySelectorAll(sel)); }
    function origAll(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
    var origStyles = origAll('style');
    var cloneStyles = all('style');
    var origLinks = origAll('link[rel~="stylesheet"]');
    var cloneLinks = all('link[rel~="stylesheet"]');
    var origImgs = origAll('img');
    var cloneImgs = all('img');
    var origCanvas = origAll('canvas');
    var cloneCanvas = all('canvas');

    // Active content and things that would stop the copy from working offline.
    all('script, noscript, iframe, frame, object, embed, applet, base, template, audio, picture source').forEach(function (e) { e.remove(); });
    all('meta[http-equiv]').forEach(function (e) {
      var h = (e.getAttribute('http-equiv') || '').toLowerCase();
      if (h === 'refresh' || h === 'content-security-policy') e.remove();
    });
    all('link[rel~="preload"], link[rel~="prefetch"], link[rel~="modulepreload"], link[rel~="dns-prefetch"], link[rel~="preconnect"], link[rel~="manifest"]').forEach(function (e) { e.remove(); });
    all('*').forEach(function (e) {
      Array.prototype.slice.call(e.attributes).forEach(function (a) {
        if (a.name.toLowerCase().indexOf('on') === 0) e.removeAttribute(a.name);
      });
      if (e.tagName === 'A') {
        var h = e.getAttribute('href');
        if (h && /^\s*javascript:/i.test(h)) {
          e.setAttribute('href', '#');
        } else if (h && h.charAt(0) !== '#') {
          var ab = absolute(h);
          if (ab) e.setAttribute('href', ab);
        }
      }
    });

    var tasks = [];

    cloneStyles.forEach(function (el, i) {
      tasks.push((async function () {
        var css = rulesText(origStyles[i]) || el.textContent;
        el.textContent = await processCss(css, base, 0);
      })());
    });

    cloneLinks.forEach(function (el, i) {
      tasks.push((async function () {
        var rel = (el.getAttribute('rel') || '').toLowerCase();
        if (rel.indexOf('alternate') >= 0) { el.remove(); return; }
        var href = absolute(el.getAttribute('href'));
        var css = rulesText(origLinks[i]);
        if (!css && href) css = await textOf(href);
        if (!css) { el.remove(); return; }
        var st = document.createElement('style');
        var media = el.getAttribute('media');
        if (media) st.setAttribute('media', media);
        st.textContent = await processCss(css, href || base, 0);
        if (el.parentNode) el.parentNode.replaceChild(st, el);
      })());
    });

    cloneImgs.forEach(function (el, i) {
      tasks.push((async function () {
        var o = origImgs[i];
        var src = (o && (o.currentSrc || o.src)) || el.getAttribute('data-src') ||
          el.getAttribute('data-lazy-src') || el.getAttribute('data-original') || el.getAttribute('src');
        el.removeAttribute('srcset');
        el.removeAttribute('sizes');
        el.removeAttribute('loading');
        var a = src ? absolute(src) : null;
        if (!a) return;
        var d = await dataUrl(a, 2500000);
        el.setAttribute('src', d || a);
      })());
    });

    cloneCanvas.forEach(function (el, i) {
      try {
        var d = origCanvas[i].toDataURL();
        var img = document.createElement('img');
        img.setAttribute('src', d);
        img.setAttribute('style', el.getAttribute('style') || '');
        if (el.getAttribute('width')) img.setAttribute('width', el.getAttribute('width'));
        if (el.getAttribute('height')) img.setAttribute('height', el.getAttribute('height'));
        el.parentNode.replaceChild(img, el);
      } catch (e) {}
    });

    all('video').forEach(function (v) {
      tasks.push((async function () {
        var poster = v.getAttribute('poster');
        var a = poster ? absolute(poster) : null;
        var d = a ? await dataUrl(a, 2500000) : null;
        if (d && v.parentNode) {
          var img = document.createElement('img');
          img.setAttribute('src', d);
          img.setAttribute('style', 'max-width:100%;height:auto');
          v.parentNode.replaceChild(img, v);
        } else {
          v.remove();
        }
      })());
    });

    all('link[rel~="icon"], link[rel~="shortcut"], link[rel~="apple-touch-icon"]').forEach(function (el) {
      tasks.push((async function () {
        var a = absolute(el.getAttribute('href'));
        var d = a ? await dataUrl(a, 300000) : null;
        if (d) el.setAttribute('href', d); else el.remove();
      })());
    });

    all('[style*="url("]').forEach(function (el) {
      tasks.push((async function () {
        el.setAttribute('style', await processCss(el.getAttribute('style'), base, 0));
      })());
    });

    await Promise.all(tasks);

    try {
      var adopted = document.adoptedStyleSheets;
      if (adopted && adopted.length) {
        var extra = '';
        Array.prototype.forEach.call(adopted, function (s) { extra += rulesText(s) + '\n'; });
        if (extra.trim()) {
          var st2 = document.createElement('style');
          st2.textContent = await processCss(extra, base, 0);
          (clone.querySelector('head') || clone).appendChild(st2);
        }
      }
    } catch (e) {}

    var head = clone.querySelector('head');
    if (head && !head.querySelector('meta[charset]')) {
      var mc = document.createElement('meta');
      mc.setAttribute('charset', 'utf-8');
      head.insertBefore(mc, head.firstChild);
    }
    var doctype = document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '<!DOCTYPE html>';
    var html = doctype + '\n<!-- Saved from ' + location.href.replace(/--/g, '-') + ' on ' + new Date().toISOString() + ' -->\n' + clone.outerHTML;

    // Sent in pieces, so a big page does not have to travel as one message.
    var STEP = 400000;
    var pos2 = 0;
    while (pos2 < html.length) {
      var end = Math.min(pos2 + STEP, html.length);
      var last = html.charCodeAt(end - 1);
      if (end < html.length && last >= 0xD800 && last <= 0xDBFF) end--;
      port.postMessage({ type: 'capchunk', part: html.slice(pos2, end) });
      pos2 = end;
    }
    return { ok: true, title: document.title, url: location.href, resources: count, skipped: skipped, length: html.length };
  }

  port.onMessage.addListener(function (msg) {
    if (msg.op === 'capture') {
      capturePage().then(
        function (d) { port.postMessage({ type: 'reply', rid: msg.rid, data: d }); },
        function (e) { port.postMessage({ type: 'reply', rid: msg.rid, data: { ok: false, msg: String(e && e.message ? e.message : e) } }); }
      );
      return;
    }
    if (msg.op === 'zoom') {
      var zv = Number(msg.value);
      if (zv > 0) {
        applyZoom(zv);
        var rec = {};
        rec[zoomKey] = zv;
        if (zv === 1) browser.storage.local.remove(zoomKey); else browser.storage.local.set(rec);
      }
      port.postMessage({ type: 'reply', rid: msg.rid, data: { ok: true, zoom: currentZoom } });
      return;
    }
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

  // ---- Tell the app which page is open (browsing history) and its HTTP status (404 page).
  function sendPage() {
    if (!isTop) return;
    var status = 0;
    try {
      var nav = performance.getEntriesByType('navigation')[0];
      if (nav && nav.responseStatus) status = nav.responseStatus;
    } catch (e) {}
    port.postMessage({ type: 'page', url: location.href, title: document.title, status: status });
  }
  document.addEventListener('DOMContentLoaded', sendPage);
  window.addEventListener('load', function () {
    sendPage();
    setTimeout(sendPage, 1500);
  });

  // ---- Tell the app whether the page is scrolled to the very top. Pull to refresh only works
  // there, so a normal scroll can never reload the page by accident. Only changes are sent.
  if (isTop) {
    var wasAtTop = true;
    window.addEventListener('scroll', function () {
      var y = window.scrollY || (document.documentElement && document.documentElement.scrollTop) || 0;
      var atTop = y <= 2;
      if (atTop !== wasAtTop) {
        wasAtTop = atTop;
        port.postMessage({ type: 'scroll', top: atTop });
      }
    }, { passive: true });
  }

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
