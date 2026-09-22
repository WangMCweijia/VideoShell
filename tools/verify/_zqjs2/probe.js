/**
 * 在 Node 沙箱里执行解析服务的混淆脚本 md5.js，把它的网络调用截下来。
 *
 * 思路：不试图逆向 jsjiami 的字符串表，而是给 DOM/jQuery/网络层装「记录型桩」，
 * 让混淆代码自己跑一遍 —— 它到底请求了哪个 URL、带什么参数，会在桩的实参里现形。
 * 真正的计算（Math / JSON / decodeURIComponent / md5）仍用真实实现，避免造出假 URL。
 */
const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const LOG = [];

function log(...a) { LOG.push(a.map(x => (typeof x === 'string' ? x : safe(x))).join(' ')); }
function safe(x) { try { return JSON.stringify(x); } catch (e) { return String(x); } }

/** 记录型链式桩：任何属性访问/调用都记账并继续链下去 */
function stub(name) {
  const target = function () { };
  return new Proxy(target, {
    get(t, p) {
      if (p === 'then' || p === Symbol.toStringTag) return undefined;
      if (p === Symbol.toPrimitive) return () => '[stub]';
      if (p === 'toString') return () => '[stub ' + name + ']';
      if (p === 'length') return 1;
      return stub(name + '.' + String(p));
    },
    apply(t, self, args) {
      log('CALL', name + '(' + args.map(a => {
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(', ').slice(0, 600) + ')');
      return stub(name + '()');
    },
    construct(t, args) {
      log('NEW', name + '(' + args.map(a => {
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(', ').slice(0, 600) + ')');
      return stub('new ' + name);
    },
    set(t, p, v) { log('SET', name + '.' + String(p) + ' = ' + safe(v).slice(0, 300)); return true; }
  });
}

// ---- 从真实解析页里取 data-* ，供 document 桩返回 ----
const parseHtml = fs.readFileSync(path.join(HERE, 'parse_probe.html'), 'utf8');
const attrs = {};
{
  const m = parseHtml.match(/<div\s+id="player-data"([\s\S]*?)>/i);
  if (m) {
    const re = /data-([a-z]+)="([^"]*)"/gi;
    let x;
    while ((x = re.exec(m[1]))) attrs[x[1]] = x[2];
  }
}
log('PARSE data-* =', JSON.stringify(attrs));

const dataEl = {
  getAttribute: (k) => {
    const key = String(k).replace(/^data-/, '');
    log('GETATTR player-data.' + key);
    return attrs[key] !== undefined ? attrs[key] : null;
  },
  dataset: new Proxy({}, {
    get: (t, p) => { log('DATASET player-data.' + String(p)); return attrs[String(p)]; }
  }),
  id: 'player-data'
};

const documentStub = {
  getElementById: (id) => {
    log('DOC getElementById(' + id + ')');
    if (String(id) === 'player-data') return dataEl;
    return stub('el#' + id);
  },
  querySelector: (s) => { log('DOC querySelector(' + s + ')'); return stub('qs:' + s); },
  querySelectorAll: () => { log('DOC querySelectorAll'); return []; },
  createElement: (t) => { log('DOC createElement(' + t + ')'); return stub('el:' + t); },
  addEventListener: () => { },
  head: stub('head'), body: stub('body'),
  cookie: '', readyState: 'complete', title: 'Player',
  documentElement: stub('documentElement')
};

const loc = {
  href: 'https://zzrs.mfdyvip.com/player/?url=co_yumkvzd6xi4agezyhm',
  origin: 'https://zzrs.mfdyvip.com', protocol: 'https:', host: 'zzrs.mfdyvip.com',
  hostname: 'zzrs.mfdyvip.com', pathname: '/player/', search: '?url=co_yumkvzd6xi4agezyhm',
  hash: '', port: '', replace: (u) => log('LOCATION.replace', u),
  assign: (u) => log('LOCATION.assign', u), reload: () => log('LOCATION.reload')
};

// 网络层：真实发出去，并把请求 + 响应都记下来（这才是我们要的东西）
const fetchLog = [];
async function realFetch(url, opts) {
  try {
    const r = await fetch(url, opts);
    const text = await r.text();
    fetchLog.push({ url: String(url), status: r.status, len: text.length, body: text.slice(0, 800) });
    return text;
  } catch (e) {
    fetchLog.push({ url: String(url), error: String(e) });
    return '';
  }
}

const win = stub('window');
Object.assign(win, { location: loc, document: documentStub });

global.window = win;
global.document = documentStub;
global.location = loc;
global.navigator = { userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36', platform: 'Linux', appVersion: '5.0' };
global.self = win;
global.top = win;
global.parent = win;
global.frames = win;
global.localStorage = { getItem: () => null, setItem: () => { }, removeItem: () => { }, clear: () => { } };
global.sessionStorage = global.localStorage;
global.XMLHttpRequest = function () { return stub('XMLHttpRequest'); };
global.fetch = function (u, o) { log('FETCH', String(u)); log('FETCH_OPTS', safe(o).slice(0, 500)); return realFetch(u, o); };
global.MuiPlayer = function (o) { log('NEW MuiPlayer', safe(o).slice(0, 800)); return stub('mui'); };
global.Hls = function () { return stub('hls'); };
global.jQuery = global.$ = function (a) {
  log('$', typeof a === 'string' ? a : safe(a).slice(0, 400));
  return stub('$(' + (typeof a === 'string' ? a : 'obj') + ')');
};
['ajax', 'get', 'post', 'getJSON', 'each', 'extend'].forEach(k => {
  global.$[k] = function () {
    log('$.' + k, JSON.stringify([...arguments].map(a => {
      try { return JSON.stringify(a); } catch (e) { return String(a); }
    })).slice(0, 900));
    return stub('$.' + k);
  };
});

// ---- 跑它 ----
const src = fs.readFileSync(path.join(HERE, 'md5.js'), 'utf8');
try {
  // 用间接 eval，保持在全局作用域
  (0, eval)(src);
  log('EVAL OK');
} catch (e) {
  log('EVAL ERROR:', e && e.message ? e.message : String(e));
  if (e && e.stack) log('STACK:', e.stack.split('\n').slice(0, 5).join(' | '));
}

setTimeout(async () => {
  console.log('================= 桩调用记录 =================');
  console.log(LOG.join('\n'));
  console.log('\n================= 真实发出的网络请求 =================');
  console.log(JSON.stringify(fetchLog, null, 2));
  console.log('\n================= 全局新增对象 =================');
  console.log('md5=' + typeof global.md5 + ' hex_md5=' + typeof global.hex_md5
    + ' MuiPlayer=' + typeof global.MuiPlayer);
}, 1500);
