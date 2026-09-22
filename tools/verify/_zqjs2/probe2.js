/**
 * 第二轮：上一轮发现混淆脚本在 `$('document').ready(fn)` 里给 `#custom-play-button`
 * 注册了 click 回调 —— 真正的取流逻辑在**点击之后**。所以这次：
 *   1) 实现一个会真正回调的迷你 jQuery（ready(fn) 直接执行 fn）；
 *   2) 捕获 #custom-play-button 的 click 回调，主动调用一次；
 *   3) 继续记录所有网络调用。
 */
const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const LOG = [];
const clickHandlers = [];
const readyFns = [];

function log(...a) { LOG.push(a.map(x => (typeof x === 'string' ? x : safe(x))).join(' ')); }
function safe(x) { try { return JSON.stringify(x); } catch (e) { return String(x); } }

function callSafely(fn, label) {
  try {
    const r = typeof fn === 'function' ? fn() : null;
    if (r && typeof r.then === 'function') { log('ASYNC', label); return r.catch(e => log('ASYNC-ERR', label, String(e && e.message || e))); }
  } catch (e) {
    log('THROW in ' + label + ': ' + String(e && e.message || e));
    if (e && e.stack) log('  at ' + e.stack.split('\n').slice(1, 4).join(' | ').trim());
  }
  return null;
}

// ------------------------------------------------------------------ 迷你 jQuery
function makeJQ(sel) {
  const api = {
    __sel: sel,
    ready(fn) { log('JQ.ready'); readyFns.push(fn); return api; },
    click(fn) { if (typeof fn === 'function') { log('JQ.click -> captured'); clickHandlers.push(fn); } else { log('JQ.click()'); } return api; },
    on(ev, fn) { log('JQ.on ' + ev); if (ev === 'click' && typeof fn === 'function') clickHandlers.push(fn); return api; },
    bind(ev, fn) { return api.on(ev, fn); },
    append(x) { log('JQ.append ' + (typeof x === 'string' ? x.slice(0, 300) : String(x))); return api; },
    prepend(x) { log('JQ.prepend ' + (typeof x === 'string' ? x.slice(0, 200) : String(x))); return api; },
    remove() { log('JQ.remove'); return api; },
    empty() { return api; }, hide() { return api; }, show() { return api; },
    css() { return api; }, attr(k) { log('JQ.attr ' + k); return api; }, removeAttr() { return api; },
    text(t) { if (t !== undefined) log('JQ.text ' + String(t).slice(0, 200)); return api; },
    html(t) { if (t !== undefined) log('JQ.html ' + String(t).slice(0, 300)); return api; },
    val() { return ''; }, width() { return 800; }, height() { return 450; },
    each() { return api; }, find() { return api; }, parent() { return api; }, children() { return api; },
    data(k) { log('JQ.data ' + k); return undefined; },
    get() { return {}; }, eq() { return api; }, first() { return api; }, closest() { return api; },
    addClass() { return api; }, removeClass() { return api; }, toggleClass() { return api; },
    is() { return false; }, length: 1, 0: {}
  };
  api[Symbol.iterator] = function* () { /* 空迭代：避免 for..of 报错 */ };
  return api;
}

global.$ = global.jQuery = function (a) {
  log('$', typeof a === 'string' ? a : (typeof a === 'function' ? '[fn]' : safe(a).slice(0, 200)));
  if (typeof a === 'function') { const p = callSafely(a, '$()'); return makeJQ('[fn]'); }
  return makeJQ(String(a));
};
['ajax', 'get', 'post', 'getJSON', 'getScript'].forEach(k => {
  global.$[k] = function () {
    const args = [...arguments].map(a => { try { return JSON.stringify(a); } catch (e) { return String(a); } });
    log('$.' + k, args.join(', ').slice(0, 900));
    // 让注册的 success 回调也跑一次，看看它期待的响应长什么样
    const cfg = arguments[0];
    if (cfg && typeof cfg === 'object' && typeof cfg.success === 'function') {
      log('$.' + k + ' -> 记录 success 回调（等真实响应后再调）');
      pendingCallbacks.push(cfg);
    }
    return makeJQ('$.' + k);
  };
});
global.$.extend = Object.assign;
global.$.each = function (o, fn) { log('$.each'); return o; };
const pendingCallbacks = [];

// ------------------------------------------------------------------ DOM
const attrs = {};
{
  const html = fs.readFileSync(path.join(HERE, 'parse_probe.html'), 'utf8');
  const m = html.match(/<div\s+id="player-data"([\s\S]*?)>/i);
  if (m) { const re = /data-([a-z]+)="([^"]*)"/gi; let x; while ((x = re.exec(m[1]))) attrs[x[1]] = x[2]; }
}
log('PARSE data-* =', JSON.stringify(attrs));

function mkEl(id) {
  const el = {
    id, tagName: 'DIV', innerHTML: '', textContent: '', value: '', href: '',
    style: {}, dataset: new Proxy({}, { get: (t, p) => attrs[String(p)] }),
    classList: { add() { }, remove() { }, contains() { return false; } },
    getAttribute(k) { log('EL#' + id + '.getAttribute ' + k); const key = String(k).replace(/^data-/, ''); return attrs[key] !== undefined ? attrs[key] : null; },
    setAttribute() { }, removeAttribute() { },
    addEventListener(ev, fn) {
      log('EL#' + id + '.addEventListener ' + ev);
      if (ev === 'click' && typeof fn === 'function') clickHandlers.push(fn);
    },
    appendChild() { }, removeChild() { }, insertBefore() { },
    play() { log('EL#' + id + '.play()'); }, pause() { }, load() { },
    currentTime: 0, duration: 0, paused: true, src: '',
    getBoundingClientRect() { return { width: 800, height: 450, top: 0, left: 0 }; }
  };
  return el;
}

const documentStub = {
  getElementById(id) { log('DOC getElementById ' + id); return mkEl(id); },
  querySelector(s) { log('DOC querySelector ' + s); return mkEl(s); },
  querySelectorAll(s) { log('DOC querySelectorAll ' + s); return []; },
  createElement(t) { log('DOC createElement ' + t); return mkEl(t); },
  addEventListener(ev, fn) { log('DOC addEventListener ' + ev); if (ev === 'DOMContentLoaded' && typeof fn === 'function') readyFns.push(fn); },
  removeEventListener() { }, execCommand() { },
  head: mkEl('head'), body: mkEl('body'), documentElement: mkEl('html'),
  cookie: '', readyState: 'complete', title: 'Player', referrer: '',
  createEvent() { return { initEvent() { } }; }
};
documentStub.head.toJSON = () => 'head';
documentStub.body.toJSON = () => 'body';

const loc = {
  href: 'https://zzrs.mfdyvip.com/player/?url=co_yumkvzd6xi4agezyhm',
  origin: 'https://zzrs.mfdyvip.com', protocol: 'https:', host: 'zzrs.mfdyvip.com',
  hostname: 'zzrs.mfdyvip.com', pathname: '/player/', search: '?url=co_yumkvzd6xi4agezyhm',
  hash: '', port: '',
  replace(u) { log('LOCATION.replace ' + u); }, assign(u) { log('LOCATION.assign ' + u); }, reload() { }
};

// ------------------------------------------------------------------ 网络
const fetchLog = [];
async function realFetch(url, opts) {
  try {
    const r = await fetch(url, opts);
    const text = await r.text();
    fetchLog.push({ url: String(url), status: r.status, len: text.length, body: text.slice(0, 1200) });
    return text;
  } catch (e) { fetchLog.push({ url: String(url), error: String(e) }); return ''; }
}

global.window = global;
global.document = documentStub;
global.location = loc;
global.navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36', platform: 'Linux' };
global.self = global; global.top = global; global.parent = global; global.frames = global;
global.localStorage = { getItem: () => null, setItem: () => { }, removeItem: () => { }, clear: () => { } };
global.sessionStorage = global.localStorage;
global.XMLHttpRequest = function () {
  const x = { open(m, u) { log('XHR.open ' + m + ' ' + u); this._u = u; }, setRequestHeader(k, v) { log('XHR.header ' + k + '=' + v); },
    send(b) { log('XHR.send ' + (b === undefined ? '' : String(b).slice(0, 300)) + ' @ ' + this._u); }, abort() { }, addEventListener() { }, onreadystatechange: null };
  return x;
};
global.fetch = function (u, o) { log('FETCH ' + String(u)); log('FETCH_OPTS ' + safe(o).slice(0, 600)); return realFetch(u, o); };
global.MuiPlayer = function (o) {
  log('NEW MuiPlayer ' + safe(o).slice(0, 1200));
  const st = makeJQ('mui');
  return Object.assign(st, { play() { log('MuiPlayer.play()'); }, video: mkEl('video'), on() { }, destroy() { } });
};
global.Hls = function () { return { loadSource(u) { log('HLS.loadSource ' + u); }, attachMedia() { }, on() { } }; };
global.Hls.isSupported = () => true;
global.console = console;
global.atob = global.atob || (s => Buffer.from(s, 'base64').toString('binary'));
global.btoa = global.btoa || (s => Buffer.from(s, 'binary').toString('base64'));

// ------------------------------------------------------------------ 跑
const src = fs.readFileSync(path.join(HERE, 'md5.js'), 'utf8');
try { (0, eval)(src); log('EVAL OK'); }
catch (e) { log('EVAL ERROR ' + String(e && e.message || e)); if (e && e.stack) log('STACK ' + e.stack.split('\n').slice(0, 6).join(' | ')); }

(async () => {
  await new Promise(r => setTimeout(r, 300));
  log('=== 触发 DOMContentLoaded 回调（' + readyFns.length + ' 个） ===');
  for (const fn of readyFns) callSafely(fn, 'ready');
  await new Promise(r => setTimeout(r, 500));

  log('=== 触发 click 回调（' + clickHandlers.length + ' 个） ===');
  for (const fn of clickHandlers) callSafely(fn, 'click');
  await new Promise(r => setTimeout(r, 4000));

  console.log('================= 桩调用记录 =================');
  console.log(LOG.join('\n'));
  console.log('\n================= 真实网络请求 =================');
  console.log(JSON.stringify(fetchLog, null, 2));
})();
