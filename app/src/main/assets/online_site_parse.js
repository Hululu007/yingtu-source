/* Online site generic parser (no per-site rule).
 * Reads the loaded page only, never mutates the DOM.
 * Exposes window.__onlineRun() and window.__onlineClickEpisode(i).
 * Results go back through the window.OnlineSite bridge as JSON strings.
 */
(function () {
  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return u || ''; }
  }
  function vis(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.width > 30 && r.height > 20;
    } catch (e) { return false; }
  }
  function txt(el) {
    return ((el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim());
  }
  function imgOf(el) {
    var im = el.querySelector ? el.querySelector('img') : null;
    if (!im) return '';
    return im.getAttribute('data-src') || im.getAttribute('data-original')
      || im.getAttribute('data-lazy-src') || im.src || '';
  }
  function firstClass(el) {
    try {
      var c = el.className;
      if (c && c.baseVal === undefined) {
        var s = String(c).split(' ')[0];
        return s || '';
      }
    } catch (e) {}
    return '';
  }

  /* List: anchors whose card carries an image + a title.
   * Cards are grouped by container signature (tag + first class);
   * the biggest group wins. Needs 3+ items, else every candidate is returned. */
  function collectList() {
    var groups = {};
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var card = null;
      if (a.closest) {
        card = a.closest('li') || a.closest('article') || a.closest('figure') || a.closest('div');
      }
      if (!card) card = a.parentElement;
      if (!card || !vis(card)) continue;
      var pic = imgOf(card);
      var title = a.getAttribute('title') || '';
      if (!title) title = txt(a);
      if (!title || title.length < 2) title = txt(card);
      if (!title || title.length < 2 || title.length > 60) continue;
      var href = abs(a.getAttribute('href'));
      if (!href || href === location.href) continue;
      if (href.indexOf('javascript:') === 0 || href.charAt(0) === '#') continue;
      var key = card.tagName + '|' + firstClass(card);
      var item = { title: title.slice(0, 60), pic: pic, url: href };
      (groups[key] = groups[key] || []).push(item);
    }
    var best = null;
    var bestCount = 0;
    for (var k in groups) {
      if (groups[k].length > bestCount) { best = groups[k]; bestCount = groups[k].length; }
    }
    var src = (best && bestCount >= 3) ? best : [];
    if (src.length === 0) {
      for (var k2 in groups) { src = src.concat(groups[k2]); }
    }
    var out = [];
    var seen = {};
    for (var j = 0; j < src.length && out.length < 60; j++) {
      var it = src[j];
      if (seen[it.url]) continue;
      seen[it.url] = 1;
      out.push(it);
    }
    return out;
  }

  function isEpisodeText(t) {
    return /第?\s*\d+\s*[集话期]/.test(t) || /^\d{1,3}$/.test(t);
  }

  /* Episodes: elements whose visible text looks like an episode number.
   * Grouped by parent signature, biggest group wins. Elements are kept
   * in window.__onlineEpEls so a tap can click the real element when
   * it has no href (JS-driven player). */
  function collectEpisodes() {
    var groups = {};
    var els = document.querySelectorAll('a,button,span,li');
    var all = [];
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var t = txt(el);
      if (!t || t.length > 12 || !isEpisodeText(t)) continue;
      if (!vis(el)) continue;
      var par = el.parentElement;
      var key = par ? (par.tagName + '|' + firstClass(par)) : 'root';
      var href = '';
      if (el.tagName === 'A') href = abs(el.getAttribute('href'));
      var item = { name: t, url: href, el: el };
      all.push(item);
      (groups[key] = groups[key] || []).push(item);
    }
    var best = null;
    var bestCount = 0;
    for (var k in groups) {
      if (groups[k].length > bestCount) { best = groups[k]; bestCount = groups[k].length; }
    }
    var src = (best && bestCount >= 3) ? best : [];
    if (src.length === 0 && all.length >= 3) src = all;
    var out = [];
    var elsKept = [];
    var seen = {};
    for (var j = 0; j < src.length && out.length < 200; j++) {
      var it = src[j];
      var dedup = it.name + '|' + it.url;
      if (seen[dedup]) continue;
      seen[dedup] = 1;
      elsKept.push(it.el);
      out.push({ name: it.name, url: it.url, index: out.length });
    }
    window.__onlineEpEls = elsKept;
    return out;
  }

  /* Meta: best-effort director / actor / year / area from labelled text.
   * Missing fields stay empty; the UI shows the fallback text instead. */
  function collectMeta() {
    var meta = { title: document.title || '', director: '', actor: '', year: '', area: '', desc: '' };
    var labels = {
      director: ['导演'],
      actor: ['主演', '演员'],
      year: ['年份', '年代'],
      area: ['地区', '国家']
    };
    var els = document.querySelectorAll('p,div,span,li,td');
    for (var i = 0; i < els.length; i++) {
      var t = txt(els[i]);
      if (!t || t.length > 120) continue;
      for (var k in labels) {
        if (meta[k]) continue;
        var arr = labels[k];
        for (var j = 0; j < arr.length; j++) {
          var lab = arr[j];
          if (t.indexOf(lab) === 0) {
            var v = t.slice(lab.length).replace(/^[:：\s]+/, '').slice(0, 60);
            if (v) meta[k] = v;
            break;
          }
        }
      }
    }
    var d = document.querySelector('meta[name="description"]');
    if (d) meta.desc = (d.getAttribute('content') || '').slice(0, 120);
    return meta;
  }

  window.__onlineRun = function () {
    try {
      if (!window.OnlineSite) return;
      var list = collectList();
      var eps = collectEpisodes();
      var meta = collectMeta();
      try { window.OnlineSite.onList(JSON.stringify(list)); } catch (e) {}
      try { window.OnlineSite.onEpisodes(JSON.stringify(eps)); } catch (e) {}
      try { window.OnlineSite.onMeta(JSON.stringify(meta)); } catch (e) {}
    } catch (e) {
      try { window.OnlineSite.onError(String((e && e.message) || e)); } catch (e2) {}
    }
  };

  window.__onlineClickEpisode = function (i) {
    try {
      var els = window.__onlineEpEls || [];
      var el = els[i];
      if (el) el.click();
    } catch (e) {}
  };
})();
