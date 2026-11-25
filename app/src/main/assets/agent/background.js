// Fetches the files of a page for the offline copy. This runs with the add-on's own rights, so it
// may load files from other sites (images, style sheets, fonts) and it sends the browser's cookies.

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || msg.op !== 'fetch') return undefined;
  return load(msg.url, msg.as, msg.max);
});

function toDataUrl(blob) {
  return new Promise(function (resolve, reject) {
    var reader = new FileReader();
    reader.onload = function () { resolve(reader.result); };
    reader.onerror = function () { reject(reader.error); };
    reader.readAsDataURL(blob);
  });
}

async function load(url, as, max) {
  var timer;
  try {
    var ctrl = new AbortController();
    timer = setTimeout(function () { ctrl.abort(); }, 20000);
    var resp = await fetch(url, { credentials: 'include', signal: ctrl.signal, redirect: 'follow' });
    if (!resp.ok) return { ok: false, error: 'HTTP ' + resp.status };
    var len = parseInt(resp.headers.get('content-length') || '0', 10);
    if (max && len > max) return { ok: false, error: 'too big' };
    var blob = await resp.blob();
    if (max && blob.size > max) return { ok: false, error: 'too big' };
    if (as === 'text') return { ok: true, text: await blob.text(), size: blob.size };
    return { ok: true, data: await toDataUrl(blob), size: blob.size };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  } finally {
    clearTimeout(timer);
  }
}
