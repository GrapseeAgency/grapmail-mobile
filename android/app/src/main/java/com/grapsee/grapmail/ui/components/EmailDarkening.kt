package com.grapsee.grapmail.ui.components

/**
 * Smart dark mode for HTML mail — the one place that decides how a sender's
 * light design is redrawn for a dark reader. The SAME script ships in
 * `ios/GrapMail/EmailDarkening.swift`; the two copies are byte-identical and
 * must be changed together (bump [SCRIPT_VERSION] on both).
 *
 * ── HOW IT RUNS ─────────────────────────────────────────────────────────────
 * The reader document carries `data-gm-dark="pending"` plus the palette on its
 * `<html>` tag ([rootAttributes]) and [pendingCss] in the app's own style
 * block, so the body is invisible (on the WebView's already-dark background)
 * until [SCRIPT] finishes and flips the attribute to `done` / `native` /
 * `error`. If the script never runs, a CSS-only animation reveals the body
 * after one second — the fallback needs no script of its own.
 *
 * ── WHAT THE SCRIPT DOES ────────────────────────────────────────────────────
 * 1. A message that declares its own dark mode (`color-scheme` /
 *    `supported-color-schemes` meta, `@media (prefers-color-scheme: dark)`,
 *    or a `color-scheme` declaration) is left alone when the engine reports
 *    dark. (If the engine does not report dark, its dark rules cannot apply,
 *    so it is darkened like any other message rather than shown as a white box.)
 * 2. Otherwise one read pass snapshots every element's computed colours, then
 *    one write pass applies inline `!important` overrides:
 *    - backgrounds: light, low-saturation fills flip lightness in HSL (white →
 *      the reader card exactly, light greys → slightly lighter than the card,
 *      pastels → dark tints of the same hue); already-dark fills and saturated
 *      brand fills / buttons are kept; gradient stops get the same mapping;
 *      a `url()` background (an image) is never touched, nor its fallback colour.
 *    - text: kept when it already reaches 4.5:1 on its new background; neutral
 *      greys flip to the palette's text tone keeping their hierarchy; coloured
 *      text keeps its hue and only moves lightness until it passes. A sender's
 *      own colour on a fill we left untouched is kept as designed.
 *    - borders/table lines: flipped like fills (light lines) or to light greys
 *      (dark lines), then nudged until they are at least as visible as they were
 *      in the original (capped at 1.6:1) — invisible spacer borders stay invisible.
 *    - images, svg, video, canvas and their subtrees are never visited.
 * 3. It reads DOM through prototype getters, so markup cannot clobber the
 *    functions it calls; it never uses eval or string timers, so it does not
 *    need any CSP allowance. It runs again harmlessly: a second call only
 *    visits elements the first call had not seen.
 */
object EmailDarkening {
    const val SCRIPT_VERSION: Int = 1

    /** Darkening applies only to a dark reader, and only while the person has not asked for original colours. */
    fun isActive(isDark: Boolean, enabled: Boolean): Boolean = isDark && enabled

    /**
     * Attributes for the document's `<html>` tag. The colours are the reader
     * card (`surfaceHex`, what white becomes) and the primary text tone.
     * Empty when inactive, which also makes [SCRIPT] a no-op on that document.
     */
    fun rootAttributes(active: Boolean, surfaceHex: String, textHex: String): String =
        if (!active) "" else
            " data-gm-dark=\"pending\" data-gm-surface=\"" + safeHex(surfaceHex, "#141416") +
                "\" data-gm-text=\"" + safeHex(textHex, "#EAEAEA") + "\""

    /** Hide-until-darkened rule for the app's own `<style data-gm-own>` block; empty when inactive. */
    fun pendingCss(active: Boolean): String = if (active) PENDING_CSS else ""

    private const val PENDING_CSS: String =
        "html[data-gm-dark=pending] body{opacity:0;animation:gm-dark-reveal 0s linear 1s forwards;}" +
            "@keyframes gm-dark-reveal{to{opacity:1;}}"

    /** `matches` is whole-input, so no anchors are needed. */
    private val HEX = Regex("#[0-9A-Fa-f]{6}")

    private fun safeHex(value: String, fallback: String): String = if (HEX.matches(value)) value else fallback

    /** The darkening script. Identical to `EmailDarkening.script` on iOS. */
    const val SCRIPT: String = """(function () {
  'use strict';
  var D = document;
  var root = null;
  function getter(proto, name) {
    var d = proto ? Object.getOwnPropertyDescriptor(proto, name) : null;
    return d && d.get ? function (o) { return d.get.call(o); } : function (o) { return o[name]; };
  }
  var rootOf = getter(Document.prototype, 'documentElement');
  var bodyOf = getter(Document.prototype, 'body');
  var sheetsOf = getter(Document.prototype, 'styleSheets');
  var parentOf = getter(Node.prototype, 'parentElement');
  var textOf = getter(Node.prototype, 'textContent');
  var nameOf = getter(Element.prototype, 'localName');
  var styleOf = getter(HTMLElement.prototype, 'style');
  var getAttr = Element.prototype.getAttribute;
  var setAttr = Element.prototype.setAttribute;
  var hasAttr = Element.prototype.hasAttribute;
  var qsa = Element.prototype.querySelectorAll;
  var makeWalker = Document.prototype.createTreeWalker;
  function finish(value) { if (root) setAttr.call(root, 'data-gm-dark', value); }
  try {
    root = rootOf(D);
    if (!root) return;
    var phase = getAttr.call(root, 'data-gm-dark');
    var state = window.__gmDarkState;
    if (!(state && state.info instanceof WeakMap && state.seen instanceof WeakSet)) state = null;
    if (phase !== 'pending' && !(phase === 'done' && state)) return;
    var body = bodyOf(D);
    if (!body) return;

    var DARK_RE = /prefers-color-scheme\s*:\s*dark/i;
    var SCHEME_RE = /color-scheme\s*:[^;}]*dark/i;
    function rulesDeclareDark(rules, depth) {
      if (!rules || depth > 4) return false;
      for (var i = 0; i < rules.length; i++) {
        var r = rules[i];
        if (r.media && DARK_RE.test(r.media.mediaText || '')) return true;
        if (r.style && /dark/i.test(r.style.getPropertyValue('color-scheme') || '')) return true;
        if (r.cssRules && rulesDeclareDark(r.cssRules, depth + 1)) return true;
      }
      return false;
    }
    function declaresDark() {
      var i, list = qsa.call(root, 'meta[name]');
      for (i = 0; i < list.length; i++) {
        var n = (getAttr.call(list[i], 'name') || '').toLowerCase();
        if (n !== 'color-scheme' && n !== 'supported-color-schemes') continue;
        var c = (getAttr.call(list[i], 'content') || '').toLowerCase();
        if (c.indexOf('dark') >= 0 && c.indexOf('only light') < 0) return true;
      }
      var sheets = sheetsOf(D) || [];
      for (i = 0; i < sheets.length; i++) {
        var owner = sheets[i].ownerNode;
        if (owner && owner.nodeType === 1 && hasAttr.call(owner, 'data-gm-own')) continue;
        var rules = null;
        try { rules = sheets[i].cssRules; } catch (e) { rules = null; }
        if (rulesDeclareDark(rules, 0)) return true;
      }
      list = qsa.call(root, 'style');
      for (i = 0; i < list.length; i++) {
        if (hasAttr.call(list[i], 'data-gm-own')) continue;
        var t = textOf(list[i]) || '';
        if (DARK_RE.test(t) || SCHEME_RE.test(t)) return true;
      }
      list = qsa.call(body, '[style*="color-scheme"]');
      for (i = 0; i < list.length; i++) {
        if (SCHEME_RE.test(getAttr.call(list[i], 'style') || '')) return true;
      }
      return false;
    }
    if (phase === 'pending' && declaresDark()) {
      var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
      if (mq && mq.matches) { finish('native'); return; }
    }

    var WHITE = [255, 255, 255, 1], BLACK = [0, 0, 0, 1];
    var MIN = 4.5, LINE_MIN = 1.6, LIMIT = 8000;
    function hex(v, fallback) {
      var m = /^#?([0-9a-f]{6})$/i.exec(v || '');
      if (!m) return fallback;
      var n = parseInt(m[1], 16);
      return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1];
    }
    function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
    function parse(v) {
      if (!v) return null;
      if (v === 'transparent') return [0, 0, 0, 0];
      var m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:\s*[,\/]\s*([\d.]+)(%?))?\s*\)$/i.exec(v);
      if (!m) return null;
      var a = m[4] === undefined ? 1 : parseFloat(m[4]) / (m[5] ? 100 : 1);
      return [+m[1], +m[2], +m[3], clamp(a, 0, 1)];
    }
    function css(c) {
      var r = function (x) { return Math.round(clamp(x, 0, 255)); };
      if (c[3] >= 1) return 'rgb(' + r(c[0]) + ', ' + r(c[1]) + ', ' + r(c[2]) + ')';
      return 'rgba(' + r(c[0]) + ', ' + r(c[1]) + ', ' + r(c[2]) + ', ' + (Math.round(c[3] * 1000) / 1000) + ')';
    }
    function over(f, b) {
      var a = f[3];
      if (a >= 1) return [f[0], f[1], f[2], 1];
      return [f[0] * a + b[0] * (1 - a), f[1] * a + b[1] * (1 - a), f[2] * a + b[2] * (1 - a), 1];
    }
    function lin(v) { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    function lum(c) { return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2]); }
    function ratio(a, b) {
      var x = lum(a), y = lum(b);
      return x > y ? (x + 0.05) / (y + 0.05) : (y + 0.05) / (x + 0.05);
    }
    function same(a, b) {
      return Math.abs(a[0] - b[0]) < 1 && Math.abs(a[1] - b[1]) < 1 && Math.abs(a[2] - b[2]) < 1 &&
        Math.abs(a[3] - b[3]) < 0.01;
    }
    function hsl(c) {
      var r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
      var mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, h = 0, s = 0, d = mx - mn;
      if (d > 1e-6) {
        s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
        if (mx === r) h = (g - b) / d + (g < b ? 6 : 0);
        else if (mx === g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h /= 6;
      }
      return [h, s, l];
    }
    function rgb(h, s, l, a) {
      function f(p, q, t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6) return p + (q - p) * 6 * t;
        if (t < 1 / 2) return q;
        if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
        return p;
      }
      var r, g, b;
      if (s <= 0) { r = g = b = l; } else {
        var q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        r = f(p, q, h + 1 / 3); g = f(p, q, h); b = f(p, q, h - 1 / 3);
      }
      return [r * 255, g * 255, b * 255, a];
    }

    var SURF = hex(getAttr.call(root, 'data-gm-surface'), [20, 20, 22, 1]);
    var TEXT = hex(getAttr.call(root, 'data-gm-text'), [234, 234, 234, 1]);
    var SH = hsl(SURF), TH = hsl(TEXT);

    function isBrand(h, y) { return h[1] >= 0.3 && h[2] >= 0.2 && h[2] <= 0.8 && y <= 0.7; }
    function flipLight(h, a) {
      var nl = clamp(SH[2] + (1 - h[2]) * 0.6, SH[2], 0.45);
      if (h[1] < 0.12 || h[2] > 0.97) return rgb(SH[0], SH[1], nl, a);
      return rgb(h[0], Math.min(h[1], 0.9) * 0.55, nl, a);
    }
    function darkBg(c) {
      if (c[3] <= 0) return c;
      var h = hsl(c), y = lum(c);
      if (y <= 0.18) return c;
      if (isBrand(h, y)) return c;
      return flipLight(h, c[3]);
    }
    function fixText(fg, ob, nb, authored) {
      if (fg[3] <= 0) return null;
      if (ratio(over(fg, nb), nb) >= MIN) return null;
      if (authored && same(ob, nb)) return null;
      var up = ratio(WHITE, nb) >= ratio(BLACK, nb);
      var h = hsl(fg), nh = h[0], ns = h[1], nl = h[2];
      if (h[1] < 0.12) {
        if (up && nl < 0.5) { nh = TH[0]; ns = TH[1]; nl = TH[2] - h[2] * (TH[2] - 0.55); }
        else if (!up && nl > 0.5) nl = 1 - nl;
      }
      var c = rgb(nh, ns, nl, fg[3]);
      for (var pass = 0; pass < 2; pass++) {
        for (var k = 0; k < 40 && ratio(over(c, nb), nb) < MIN; k++) {
          nl = clamp(nl + (up ? 0.025 : -0.025), 0, 1);
          c = rgb(nh, ns, nl, c[3]);
          if (nl === 0 || nl === 1) break;
        }
        if (ratio(over(c, nb), nb) >= MIN) return c;
        c = rgb(nh, ns, nl, 1);
      }
      return up ? WHITE : BLACK;
    }
    function fixLine(c, oIn, oOut, nIn, nOut) {
      if (c[3] <= 0) return null;
      var h = hsl(c), cand;
      if (isBrand(h, lum(c))) cand = c;
      else if (h[2] >= 0.5) cand = flipLight(h, c[3]);
      else {
        var dl = 0.35 + (0.5 - h[2]) * 0.6;
        cand = h[1] < 0.12 ? rgb(TH[0], TH[1], dl, c[3]) : rgb(h[0], h[1], dl, c[3]);
      }
      var want = Math.min(LINE_MIN, Math.max(ratio(over(c, oOut), oOut), ratio(over(c, oIn), oIn)));
      var vis = function (x) { return Math.max(ratio(over(x, nOut), nOut), ratio(over(x, nIn), nIn)); };
      var up = ratio(WHITE, nOut) >= ratio(BLACK, nOut);
      var ch = hsl(cand), l = ch[2];
      for (var k = 0; k < 40 && vis(cand) < want - 0.01; k++) {
        l = clamp(l + (up ? 0.02 : -0.02), 0, 1);
        cand = rgb(ch[0], ch[1], l, cand[3]);
        if (l === 0 || l === 1) break;
      }
      return same(cand, c) ? null : cand;
    }
    function fixGradient(v, ob, nb) {
      var so = [], sn = [];
      var out = v.replace(/rgba?\([^)]*\)/gi, function (m) {
        var c = parse(m);
        if (!c) return m;
        var n = darkBg(c);
        so.push(over(c, ob)); sn.push(over(n, nb));
        return css(n);
      });
      function avg(list) {
        var s = [0, 0, 0];
        for (var i = 0; i < list.length; i++) { s[0] += list[i][0]; s[1] += list[i][1]; s[2] += list[i][2]; }
        return [s[0] / list.length, s[1] / list.length, s[2] / list.length, 1];
      }
      return { css: out, o: so.length ? avg(so) : null, n: sn.length ? avg(sn) : null };
    }

    var SKIP = { img: 1, picture: 1, video: 1, audio: 1, svg: 1, canvas: 1, object: 1, embed: 1, iframe: 1,
      math: 1, style: 1, script: 1, template: 1, meta: 1, link: 1, title: 1, noscript: 1, source: 1,
      track: 1, map: 1, area: 1 };
    var SIDES = ['top', 'right', 'bottom', 'left'];
    if (!state) { state = { info: new WeakMap(), seen: new WeakSet() }; window.__gmDarkState = state; }
    var bodyColor = parse(getComputedStyle(body).getPropertyValue('color')) || TEXT;
    var base = { o: WHITE, n: SURF, img: false, fo: bodyColor, fc: bodyColor };
    state.info.set(body, base);

    var walker = makeWalker.call(D, body, 1, {
      acceptNode: function (node) { return SKIP[nameOf(node)] ? 2 : 1; }
    });
    var jobs = [], node, count = 0;
    while ((node = walker.nextNode()) && count < LIMIT) {
      count++;
      if (state.seen.has(node)) continue;
      state.seen.add(node);
      try {
        var p = parentOf(node);
        var pi = (p && state.info.get(p)) || base;
        var cs = getComputedStyle(node);
        var set = [];
        var o = pi.o, n = pi.n, img = pi.img;
        var bgc = parse(cs.getPropertyValue('background-color')) || [0, 0, 0, 0];
        var bgi = cs.getPropertyValue('background-image') || 'none';
        if (bgi !== 'none' && /url\(/i.test(bgi)) {
          if (bgc[3] > 0) { o = over(bgc, o); n = over(bgc, n); }
          img = true;
        } else {
          if (bgc[3] > 0) {
            var nbg = darkBg(bgc);
            if (!same(nbg, bgc)) set.push(['background-color', css(nbg)]);
            o = over(bgc, o); n = over(nbg, n);
            if (bgc[3] >= 0.9) img = false;
          }
          if (bgi !== 'none' && /gradient/i.test(bgi)) {
            var g = fixGradient(bgi, o, n);
            if (g.css !== bgi) set.push(['background-image', g.css]);
            if (g.o && g.n) { o = g.o; n = g.n; img = false; }
          }
        }
        var fg = parse(cs.getPropertyValue('color'));
        var fc = pi.fc, fo = pi.fo, colorWritten = false;
        if (fg) {
          fo = fg;
          var nf = img ? null : fixText(fg, o, n, !same(fg, bodyColor));
          fc = nf || fg;
          if (nf || !same(pi.fc, pi.fo)) { set.push(['color', css(fc)]); colorWritten = true; }
        }
        for (var s = 0; s < 4; s++) {
          var side = 'border-' + SIDES[s] + '-';
          var bw = parseFloat(cs.getPropertyValue(side + 'width')) || 0;
          var bs = cs.getPropertyValue(side + 'style');
          if (bw <= 0 || bs === 'none' || bs === 'hidden') continue;
          var bc = parse(cs.getPropertyValue(side + 'color'));
          if (!bc) continue;
          var nl = img ? null : fixLine(bc, o, pi.o, n, pi.n);
          if (nl || colorWritten) set.push([side + 'color', css(nl || bc)]);
        }
        state.info.set(node, { o: o, n: n, img: img, fo: fo, fc: fc });
        if (set.length) jobs.push([node, set]);
      } catch (e) { }
    }
    for (var j = 0; j < jobs.length; j++) {
      try {
        var st = styleOf(jobs[j][0]);
        for (var q = 0; q < jobs[j][1].length; q++) st.setProperty(jobs[j][1][q][0], jobs[j][1][q][1], 'important');
      } catch (e) { }
    }
    finish('done');
  } catch (e) {
    finish('error');
  }
})();"""
}
