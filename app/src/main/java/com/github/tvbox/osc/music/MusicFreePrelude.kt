package com.github.tvbox.osc.music

/**
 * MusicFree 插件的 CommonJS 运行环境（纯 JS 文本，注入到 QuickJS）。
 *
 * MusicFree 插件不是「事件式」而是「module 式」：
 *
 *     module.exports = {
 *         platform: 'qq',
 *         search: async (query, page, type) => ({ isEnd, data }),
 *         getMediaSource: async (musicItem, quality) => ({ url }),
 *         getLyric: async (musicItem) => string,
 *         getAlbumInfo, getTopLists, getMusicInfo ...
 *     }
 *
 * 所以壳必须提供 module / exports / require / console，
 * 然后把 module.exports 暴露出来给 Kotlin 调。
 *
 * ============================================================
 * 白名单 require（对齐 MusicFree 本体）
 * --------------------------------------
 * 本体允许的是：axios / cheerio / crypto-js / dayjs / big-integer / qs / he / webdav
 *
 * 这里在 JS 侧再套一层垫片，把真正需要原生能力的调用
 * （HTTP、加解密、DOM 解析、编码转换）转交给 Kotlin 桥。
 * 好处是：不用在 QuickJS 里塞进 500KB 的 cheerio，
 * 而插件代码里的 `cheerio.load(html).xxx` 又能原样跑。
 *
 * ============================================================
 * 桥函数命名
 * --------------------------------------
 *   __mf_http(method, url, headersJson, body, timeoutMs)  -> JSON 字符串
 *   __mf_crypto(op, ...)                                   -> 字符串
 *   __mf_cheerioLoad(html)                                 -> 句柄 id
 *   __mf_cheerioSelect(hid, sel)                           -> JSON 数组
 *   __mf_log(level, text)
 *   __mf_pluginLoaded()                                     -> 插件注册成功时回调，Kotlin 侧据此标记 ready
 */
object MusicFreePrelude {

    fun build(): String = HEAD + SHIMS + FOOT

    // ============================================================ 骨架

    private const val HEAD = """
/* ---------------------------------------------------------------
 * MusicFree 插件宿主 prelude
 * 由 AVBox 注入，不是插件内容
 * --------------------------------------------------------------- */

// 保证全局对象存在
var globalThis = this;

// ---------- module / exports ----------
var module = { exports: {} };
var exports = module.exports;

// ---------- console ----------
var console = {
    log: function () { __mf_log('i', __mf_fmt(arguments)); },
    info: function () { __mf_log('i', __mf_fmt(arguments)); },
    warn: function () { __mf_log('w', __mf_fmt(arguments)); },
    error: function () { __mf_log('e', __mf_fmt(arguments)); },
    debug: function () { __mf_log('i', __mf_fmt(arguments)); }
};

function __mf_fmt(args) {
    var out = [];
    for (var i = 0; i < args.length; i++) {
        var v = args[i];
        if (typeof v === 'string') { out.push(v); }
        else if (v === null) { out.push('null'); }
        else if (v === undefined) { out.push('undefined'); }
        else {
            try { out.push(JSON.stringify(v)); } catch (e) { out.push(String(v)); }
        }
    }
    return out.join(' ');
}

// ---------- 基础工具 ----------
var setTimeout = function (fn, ms) {
    // QuickJS 里没有真定时器，直接同步执行（插件里多用于小延迟，无害）
    if (typeof fn === 'function') { fn(); }
    return 0;
};
var clearTimeout = function () {};
var setInterval = function () { return 0; };
var clearInterval = function () {};

// 最小 URL 编码支持。
// 注意：QuickJS 原生提供 encodeURIComponent / decodeURIComponent，
// 这里只是包一层短名字，别写成 __mf_encodeURIComponent 那种自引用（会 undefined）。
var __mf_encodeURIComponent = encodeURIComponent;
var __mf_encode = encodeURIComponent;
var __mf_decodeURIComponent = decodeURIComponent;
"""

    // ============================================================ 垫片

    private const val SHIMS = """
// =================================================================
// require 白名单
// =================================================================

var __mf_modules = {};

function require(name) {
    var n = String(name).toLowerCase();
    if (__mf_modules[n]) { return __mf_modules[n]; }
    throw new Error('MusicFree: 不支持的模块 ' + name);
}

// ---------------- axios ----------------
(function () {
    function buildUrl(url, params) {
        if (!params) { return url; }
        var qs = [];
        for (var k in params) {
            if (!Object.prototype.hasOwnProperty.call(params, k)) { continue; }
            var v = params[k];
            if (v === null || v === undefined) { continue; }
            qs.push(__mf_encodeURIComponent(k) + '=' + __mf_encodeURIComponent(String(v)));
        }
        if (qs.length === 0) { return url; }
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + qs.join('&');
    }

    function normalizeResponse(rawText) {
        var resp = { status: 200, statusText: 'OK', headers: {}, data: rawText };
        try {
            var parsed = JSON.parse(rawText);
            resp.status = parsed.__status || 200;
            resp.headers = parsed.__headers || {};
            resp.data = parsed.__data;
            resp.statusText = parsed.__statusText || 'OK';
        } catch (e) {
            // 不是我们包装的格式，说明 Kotlin 直接回了原文
        }
        return resp;
    }

    function request(config) {
        config = config || {};
        var method = String(config.method || 'get').toUpperCase();
        var url = buildUrl(config.url || config.uri || '', config.params);
        var headers = config.headers || {};
        var body = config.data;
        if (typeof body === 'object' && body !== null) {
            body = JSON.stringify(body);
        }
        var timeout = config.timeout || 10000;
        return new Promise(function (resolve, reject) {
            try {
                var raw = __mf_http(method, url, JSON.stringify(headers), body || '', timeout);
                if (raw === null || raw === undefined || raw === '') {
                    reject(new Error('MusicFree: HTTP 空响应 ' + url));
                    return;
                }
                resolve(normalizeResponse(raw));
            } catch (e) {
                reject(e);
            }
        });
    }

    var axios = function (config) { return request(config); };
    axios.request = request;
    axios.get = function (url, config) {
        config = config || {};
        config.url = url;
        config.method = 'get';
        return request(config);
    };
    axios.post = function (url, data, config) {
        config = config || {};
        config.url = url;
        config.method = 'post';
        config.data = data;
        return request(config);
    };
    axios.defaults = { headers: {} };
    axios.create = function () { return axios; };
    __mf_modules['axios'] = axios;
})();

// ---------------- cheerio（转发给 Kotlin 的真实现） ----------------
//
// Kotlin 侧 __mf_cheerioSelect(hid, sel) 返回：
//   [ {tag, text, ownText, html, innerHtml, attrs:{...}}, ... ]
//
// 这里把它包成 cheerio 风格的链式对象。
// 支持的调用：.text() .attr() .html() .find() .each() .eq() .length
(function () {
    function wrap(hid, items) {
        var q = {
            length: items.length,
            _items: items,

            text: function () {
                if (items.length === 0) { return ''; }
                var buf = [];
                for (var i = 0; i < items.length; i++) { buf.push(items[i].text || ''); }
                return buf.join('');
            },

            html: function () {
                if (items.length === 0) { return null; }
                return items[0].innerHtml || '';
            },

            attr: function (name) {
                if (items.length === 0) { return undefined; }
                var a = items[0].attrs || {};
                var v = a[String(name)];
                return v === undefined ? undefined : v;
            },

            each: function (fn) {
                for (var i = 0; i < items.length; i++) {
                    fn.call(items[i], i, makeNode(hid, items[i]));
                }
                return q;
            },

            eq: function (i) {
                var idx = i < 0 ? items.length + i : i;
                return wrap(hid, idx >= 0 && idx < items.length ? [items[idx]] : []);
            },

            first: function () { return q.eq(0); },
            last: function () { return q.eq(-1); },

            find: function (sel) {
                // 先在本节点 html 里查，最简单也最常用
                if (items.length === 0) { return wrap(hid, []); }
                var hid2 = __mf_cheerioLoad(items[0].html || '');
                return wrap(hid2, __mf_cheerioSelectJson(hid2, sel));
            },

            map: function (fn) {
                var out = [];
                for (var i = 0; i < items.length; i++) {
                    out.push(fn.call(items[i], i, makeNode(hid, items[i])));
                }
                return wrap(hid, out);
            },

            toString: function () { return q.text(); }
        };
        return q;
    }

    // 单个节点（回调里的 el）
    function makeNode(hid, item) {
        return {
            tagName: item.tag,
            text: function () { return item.text || ''; },
            innerText: item.text || '',
            html: function () { return item.innerHtml || ''; },
            attribs: item.attrs || {},
            attr: function (n) {
                var a = item.attrs || {};
                return a[String(n)];
            }
        };
    }

    var cheerio = function (html) { return cheerio.load(html); };

    // cheerio.load(html) 返回的是「根选择器」：
    // 它自己不是结果集，而是要在文档上继续 find。
    // 注意不能用 ':root' 之类的伪类去查（jsoup 不支持，会返回空）。
    cheerio.load = function (html) {
        var hid = __mf_cheerioLoad(String(html));

        function rootQuery(sel) {
            var items = __mf_cheerioSelectJson(hid, sel);
            return wrap(hid, items);
        }

        var root = rootQuery('body');
        // 根对象上额外挂 find / text / html
        root.find = function (sel) { return rootQuery(sel); };
        root.text = function () { return __mf_cheerioText(hid); };
        root.html = function () { return String(html); };
        root._hid = hid;
        return root;
    };

    cheerio.html = function (html) {
        return cheerio.load(html);
    };

    __mf_modules['cheerio'] = cheerio;
})();

// ---------------- crypto-js ----------------
(function () {
    function wrap(fn) {
        return {
            toString: function () { return fn.apply(null, arguments); }
        };
    }

    var MD5 = function () {
        var args = arguments;
        return { toString: function () { return __mf_crypto('md5', args[0]); } };
    };
    MD5.prototype.toString = function () { return MD5.apply(null, arguments).toString(); };

    var SHA1 = function (msg) {
        return { toString: function () { return __mf_crypto('sha1', msg); } };
    };
    var SHA256 = function (msg) {
        return { toString: function () { return __mf_crypto('sha256', msg); } };
    };

    var AES = {
        encrypt: function (msg, key, opt) {
            return { toString: function () { return __mf_crypto('aesEncrypt', msg, key, JSON.stringify(opt || {})); } };
        },
        decrypt: function (msg, key, opt) {
            return { toString: function () { return __mf_crypto('aesDecrypt', msg, key, JSON.stringify(opt || {})); } };
        }
    };
    AES.pad = {};
    AES.mode = { CBC: 'CBC', ECB: 'ECB', CFB: 'CFB', OFB: 'OFB', CTR: 'CTR' };

    var enc = {
        Utf8: { parse: function (s) { return String(s); }, stringify: function (s) { return String(s); } },
        Base64: {
            parse: function (s) { return __mf_crypto('base64Decode', s); },
            stringify: function (s) { return __mf_crypto('base64Encode', s); }
        },
        Hex: {
            parse: function (s) { return __mf_crypto('hexDecode', s); },
            stringify: function (s) { return __mf_crypto('hexEncode', s); }
        }
    };

    var cryptoJs = {
        MD5: MD5,
        SHA1: SHA1,
        SHA256: SHA256,
        HmacSHA1: function (m, k) {
            return { toString: function () { return __mf_crypto('hmacSha1', m, k); } };
        },
        HmacSHA256: function (m, k) {
            return { toString: function () { return __mf_crypto('hmacSha256', m, k); } };
        },
        AES: AES,
        enc: enc,
        lib: { WordArray: { create: function (x) { return { toString: function () { return enc.Utf8.stringify(x); } }; } } }
    };
    __mf_modules['crypto-js'] = cryptoJs;
})();

// ---------------- dayjs ----------------
(function () {
    function dayjs(input) {
        var d = input === undefined ? new Date() : (input instanceof Date ? input : new Date(input));
        return {
            valueOf: function () { return d.getTime(); },
            format: function (fmt) {
                if (!fmt) { return d.toISOString(); }
                var pad = function (n, w) {
                    var s = String(n); while (s.length < w) { s = '0' + s; } return s;
                };
                return String(fmt)
                    .replace(/YYYY/g, pad(d.getFullYear(), 4))
                    .replace(/MM/g, pad(d.getMonth() + 1, 2))
                    .replace(/DD/g, pad(d.getDate(), 2))
                    .replace(/HH/g, pad(d.getHours(), 2))
                    .replace(/mm/g, pad(d.getMinutes(), 2))
                    .replace(/ss/g, pad(d.getSeconds(), 2));
            },
            unix: function () { return Math.floor(d.getTime() / 1000); },
            add: function (n, unit) {
                var copy = new Date(d.getTime());
                var ms = 0;
                if (unit === 'day') { ms = n * 86400000; }
                else if (unit === 'hour') { ms = n * 3600000; }
                else if (unit === 'minute') { ms = n * 60000; }
                else if (unit === 'second') { ms = n * 1000; }
                else { ms = n; }
                return dayjs(new Date(copy.getTime() + ms));
            }
        };
    }
    __mf_modules['dayjs'] = dayjs;
})();

// ---------------- big-integer ----------------
(function () {
    function BigInteger(v) { this.v = String(v === undefined ? 0 : v); }
    BigInteger.prototype.toString = function () { return this.v; };
    BigInteger.prototype.add = function (o) { return BigInteger(__mf_bigop('add', this.v, String(o))); };
    BigInteger.prototype.multiply = function (o) { return BigInteger(__mf_bigop('mul', this.v, String(o))); };
    BigInteger.prototype.divide = function (o) { return BigInteger(__mf_bigop('div', this.v, String(o))); };
    BigInteger.prototype.mod = function (o) { return BigInteger(__mf_bigop('mod', this.v, String(o))); };
    BigInteger.prototype.subtract = function (o) { return BigInteger(__mf_bigop('sub', this.v, String(o))); };
    var bigInt = function (v) { return new BigInteger(v); };
    bigInt.zero = new BigInteger('0');
    bigInt.one = new BigInteger('1');
    __mf_modules['big-integer'] = bigInt;
})();

// ---------------- qs ----------------
(function () {
    var qs = {
        stringify: function (obj) {
            var parts = [];
            for (var k in obj) {
                if (!Object.prototype.hasOwnProperty.call(obj, k)) { continue; }
                var v = obj[k];
                if (v === null || v === undefined) { continue; }
                parts.push(__mf_encodeURIComponent(k) + '=' + __mf_encodeURIComponent(String(v)));
            }
            return parts.join('&');
        },
        parse: function (s) {
            var out = {};
            String(s || '').split('&').forEach(function (kv) {
                if (!kv) { return; }
                var i = kv.indexOf('=');
                var k = i < 0 ? kv : kv.substring(0, i);
                var v = i < 0 ? '' : kv.substring(i + 1);
                try { out[decodeURIComponent(k)] = decodeURIComponent(v); } catch (e) { out[k] = v; }
            });
            return out;
        }
    };
    __mf_modules['qs'] = qs;
})();

// ---------------- he ----------------
(function () {
    var he = {
        decode: function (s) {
            return String(s)
                .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
                .replace(/"/g, String.fromCharCode(34))
                .replace(/&#39;/g, String.fromCharCode(39))
                .replace(/'/g, String.fromCharCode(39))
                .replace(/&nbsp;/g, ' ');
        },
        encode: function (s) {
            var q = String.fromCharCode(34);
            var a = String.fromCharCode(39);
            return String(s)
                .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '"').replace(/'/g, '&#39;');
        }
    };
    __mf_modules['he'] = he;
})();

// ---------------- webdav（极少用到，给个空实现避免崩） ----------------
(function () {
    __mf_modules['webdav'] = {
        createClient: function () {
            return { getDirectoryContents: function () { return Promise.resolve([]); } };
        }
    };
})();

// =================================================================
// 插件结果访问器（Kotlin 侧用这几个函数，避免直接读 exports）
// =================================================================

// Parcel/webpack 打包的插件（如小秋音乐、部分 babel 产物）只导出 default：
//   export default { platform, search, getMediaSource, ... }
// 编译后 module.exports = { default: {...}, __esModule: true }，
// 此时 module.exports.search 是 undefined，插件会"装上但搜不到歌"。
// 这里统一取一次：exports 自身有 search 就用它，否则退回 exports.default。
function __mf_exports() {
    var e = module.exports || {};
    if (typeof e.search !== 'function' && e['default']) {
        var d = e['default'];
        if (d && (typeof d.search === 'function' || typeof d.getMediaSource === 'function')) {
            return d;
        }
    }
    return e;
}

function __mf_platform() {
    var e = __mf_exports();
    return String(e.platform || '');
}
function __mf_versions() {
    var e = __mf_exports();
    try { return JSON.stringify(e.version || {}); } catch (x) { return '{}'; }
}
// 调搜索：返回 JSON 字符串 {isEnd, data:[...]}
function __mf_search(query, page, type) {
    var e = __mf_exports();
    if (typeof e.search !== 'function') {
        return JSON.stringify({ error: '插件没有实现 search' });
    }
    return Promise.resolve(e.search(String(query), Number(page), type || 'music'))
        .then(function (r) {
            return JSON.stringify(r === undefined || r === null ? { isEnd: true, data: [] } : r);
        })
        .catch(function (th) {
            return JSON.stringify({ error: String((th && th.message) || th) });
        });
}
// 调取链：返回 JSON 字符串 {url, headers} 或 {error}
function __mf_getMediaSource(musicItemJson, quality) {
    var e = __mf_exports();
    if (typeof e.getMediaSource !== 'function') {
        return JSON.stringify({ error: '插件没有实现 getMediaSource' });
    }
    var item;
    try { item = JSON.parse(musicItemJson); } catch (x) { item = {}; }
    return Promise.resolve(e.getMediaSource(item, quality || 'standard'))
        .then(function (r) {
            if (typeof r === 'string') { return JSON.stringify({ url: r }); }
            return JSON.stringify(r === undefined || r === null ? { error: '插件没返回地址' } : r);
        })
        .catch(function (th) {
            return JSON.stringify({ error: String((th && th.message) || th) });
        });
}
// 调歌词：返回字符串
function __mf_getLyric(musicItemJson) {
    var e = __mf_exports();
    if (typeof e.getLyric !== 'function') { return Promise.resolve(''); }
    var item;
    try { item = JSON.parse(musicItemJson); } catch (x) { item = {}; }
    return Promise.resolve(e.getLyric(item))
        .then(function (r) { return String(r === undefined || r === null ? '' : r); })
        .catch(function () { return ''; });
}
function __mf_hasMethod(name) {
    var e = __mf_exports();
    return typeof e[String(name)] === 'function';
}
"""

    // ============================================================ 收尾

    private const val FOOT = """
// 通知 Kotlin：插件已注册完毕
(function () {
    try { __mf_pluginLoaded(__mf_platform()); } catch (e) {}
})();
"""
}