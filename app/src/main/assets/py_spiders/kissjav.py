import base64
import gzip
import html as _html
import http.client
import re
import ssl
import time
import urllib.parse
import urllib.request

try:
    from base.spider import BaseSpider
except Exception:
    try:
        from base import BaseSpider
    except Exception:
        class BaseSpider:
            pass

BASE = 'https://kissjav.li'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

CATEGORIES = [
    ('vip', 'KVIP'),
    ('jvip', 'JVIP'),
    ('korean-porn', 'Korean Porn'),
    ('korean-bj', 'Korean BJ'),
    ('fc2ppv', 'FC2PPV'),
    ('jav', 'Uncensored'),
    ('myfans', 'MyFans'),
    ('ipcam', 'IPCAM'),
    ('toilet', 'Toilet'),
    ('voyeur-jp', 'VoyeurJP'),
]

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE

CACHE = {}
CACHE_TTL = 180

RE_CARD = re.compile(
    r'<a\s+href="https?://kissjav\.li/(video/\d+/[^"]+/)"[^>]*title="([^"]+)"'
    r'[\s\S]{0,2000}?data-original="([^"]+)"'
    r'[\s\S]{0,1000}?<div class="time">([^<]*)</div>',
    re.S)
RE_CARD_NODUR = re.compile(
    r'<a\s+href="https?://kissjav\.li/(video/\d+/[^"]+/)"[^>]*title="([^"]+)"'
    r'[\s\S]{0,2000}?data-original="([^"]+)"',
    re.S)
RE_PAGE = re.compile(r'href="/categories/[^"]+/(\d+)/"')
RE_SEARCH_PAGE = re.compile(r'href="/search/[^"]+/(\d+)/"')
RE_OG_TITLE = re.compile(r'property="og:title" content="([^"]+)"')
RE_OG_IMAGE = re.compile(r'property="og:image" content="([^"]+)"')
RE_LD_DURATION = re.compile(r'"duration":\s*"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?"')
RE_LD_DATE = re.compile(r'"uploadDate":\s*"([^"]+)"')
RE_FLASHVARS = re.compile(r"video_url:\s*'([A-Za-z0-9+/=]+)'")
RE_CATS = re.compile(r"video_categories:\s*'([^']*)'")
RE_STILL = re.compile(r'//assets6\.cdnhop\.com/contents/videos_screenshots/\d+/\d+/(?:preview\.jpg|preview\.mp4\.jpg|preview_preview\.mp4\.jpg|320x180/1\.jpg)')
RE_VID = re.compile(r'video/(\d+)')


def _now():
    return time.time()


def _http_get(url, referer=None, timeout=35):
    hdr = {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9,ko;q=0.8',
        'Accept-Encoding': 'identity',
        'Connection': 'close',
    }
    if referer:
        hdr['Referer'] = referer
    for _ in range(4):
        try:
            req = urllib.request.Request(url, headers=hdr)
            with urllib.request.urlopen(req, timeout=timeout, context=CTX) as resp:
                try:
                    data = resp.read()
                except http.client.IncompleteRead as e:
                    data = e.partial
        except Exception:
            time.sleep(2)
            continue
        if data and len(data) > 300:
            if data[:2] == b'\x1f\x8b':
                try:
                    data = gzip.decompress(data)
                except Exception:
                    pass
            for cs in ('utf-8', 'gbk', 'gb18030'):
                try:
                    return data.decode(cs)
                except Exception:
                    continue
            return data.decode('utf-8', 'replace')
        time.sleep(2)
    return None


def _txt(s):
    if not s:
        return ''
    s = re.sub(r'<[^>]+>', ' ', s)
    s = _html.unescape(s)
    return re.sub(r'\s+', ' ', s).strip()


def _abs_pic(p):
    if not p:
        return ''
    if p.startswith('//'):
        return 'https:' + p
    if p.startswith('/'):
        return BASE + p
    return p


def _vid_num(vod_id):
    m = RE_VID.search(vod_id or '')
    return m.group(1) if m else ''


def _check_still(url):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        with urllib.request.urlopen(req, timeout=15, context=CTX) as resp:
            if resp.status != 200:
                return False
            try:
                b = resp.read(16)
            except http.client.IncompleteRead as e:
                b = e.partial
            if not b:
                return False
            if b[:2] == b'\xff\xd8':
                return True
            if b[:8] == b'\x89PNG\r\n\x1a\n':
                return True
            return False
    except Exception:
        return False


def _page(url):
    ck = 'P:' + url
    v = CACHE.get(ck)
    if v and _now() - v[0] < CACHE_TTL:
        return v[1]
    body = _http_get(url, referer=BASE + '/')
    if body and len(body) > 300:
        if len(CACHE) > 400:
            CACHE.clear()
        CACHE[ck] = (_now(), body)
    return body


def _parse_list(body):
    out = []
    if not body:
        return out
    seen = set()
    for m in RE_CARD.finditer(body):
        vid = urllib.parse.unquote(m.group(1))
        if vid in seen:
            continue
        name = _txt(m.group(2))
        if not name:
            continue
        seen.add(vid)
        out.append({
            'vod_id': vid,
            'vod_name': name,
            'vod_pic': _abs_pic(m.group(3)),
            'vod_remarks': _txt(m.group(4)),
        })
    if not out:
        for m in RE_CARD_NODUR.finditer(body):
            vid = urllib.parse.unquote(m.group(1))
            if vid in seen:
                continue
            name = _txt(m.group(2))
            if not name:
                continue
            seen.add(vid)
            out.append({
                'vod_id': vid,
                'vod_name': name,
                'vod_pic': _abs_pic(m.group(3)),
                'vod_remarks': '',
            })
    return out


def _pagecount(body, pat):
    pages = [int(x) for x in pat.findall(body or '')]
    return max(pages) if pages else 0


def _fmt_dur(h, m, s):
    try:
        h = int(h or 0)
        m = int(m or 0)
        s = int(s or 0)
    except Exception:
        return ''
    if h > 0:
        return '%d:%02d:%02d' % (h, m, s)
    if m > 0 or s > 0:
        return '%d:%02d' % (m, s)
    return ''


def _detail(vod_id):
    url = BASE + '/' + urllib.parse.quote(vod_id, safe='/')
    body = _page(url)
    d = {
        'vod_id': vod_id,
        'vod_name': '',
        'vod_pic': '',
        'vod_content': '',
        'vod_remarks': '',
        'type_name': '',
        'stills': [],
        'stream': '',
        'detail_url': url,
    }
    if not body:
        return d
    m = RE_OG_TITLE.search(body)
    if m:
        d['vod_name'] = _txt(m.group(1))
    if not d['vod_name']:
        m = re.search(r'<title>([^<]+)</title>', body)
        if m:
            d['vod_name'] = _txt(m.group(1))
    m = RE_OG_IMAGE.search(body)
    if m:
        d['vod_pic'] = _abs_pic(m.group(1))
    dm = RE_LD_DURATION.search(body)
    if dm:
        d['vod_remarks'] = _fmt_dur(dm.group(1), dm.group(2), dm.group(3))
    cm = RE_CATS.search(body)
    if cm:
        cats = [c.strip() for c in cm.group(1).split(',') if c.strip()]
        d['type_name'] = ' / '.join(cats)
    parts = []
    if d['type_name']:
        parts.append('Categories: ' + d['type_name'])
    if d['vod_remarks']:
        parts.append('Duration: ' + d['vod_remarks'])
    dt = RE_LD_DATE.search(body)
    if dt:
        parts.append('Uploaded: ' + dt.group(1)[:10])
    if parts:
        d['vod_content'] = ' | '.join(parts)
    seen = set()
    vidnum = _vid_num(vod_id)
    for s in RE_STILL.findall(body):
        u = _abs_pic(s)
        if not u or u in seen:
            continue
        seen.add(u)
        if vidnum and ('/%s/' % vidnum) not in u:
            continue
        if u == d['vod_pic']:
            continue
        if not _check_still(u):
            continue
        d['stills'].append(u)
    fm = RE_FLASHVARS.search(body)
    if fm:
        try:
            raw = base64.b64decode(fm.group(1)).decode('utf-8', 'replace').strip()
            if raw.startswith('http'):
                d['stream'] = raw
        except Exception:
            pass
    return d


def _norm_id(ids):
    if isinstance(ids, (list, tuple)):
        return str(ids[0]) if ids else ''
    return str(ids)


def _norm_pg(pg):
    try:
        return int(pg)
    except Exception:
        try:
            return int(str(pg).strip())
        except Exception:
            return 1


class Spider(BaseSpider):

    def getName(self):
        return '🔞KissJAV'

    def getDependence(self):
        return []

    def init(self, extend=None):
        pass

    def destroy(self):
        pass

    def homeContent(self, filter=False):
        return {'class': [{'type_id': tid, 'type_name': name} for tid, name in CATEGORIES], 'filters': {}}

    def homeVideoContent(self):
        items = _parse_list(_page(BASE + '/'))
        return {'list': items, 'page': 1, 'pagecount': 0, 'total': 0}

    def categoryContent(self, tid, pg, filter=False, extend=None):
        tid = _norm_id(tid)
        pg = _norm_pg(pg)
        if pg < 1:
            pg = 1
        url = '%s/categories/%s/' % (BASE, tid) if pg == 1 else '%s/categories/%s/%d/' % (BASE, tid, pg)
        body = _page(url)
        items = _parse_list(body)
        return {'list': items, 'page': pg, 'pagecount': _pagecount(body, RE_PAGE), 'total': 0}

    def detailContent(self, ids):
        vid = _norm_id(ids)
        vid = urllib.parse.unquote(vid).lstrip('/')
        d = _detail(vid)
        play_url = '正片$kissjav:%s' % vid if d['stream'] else ''
        return {'list': [{
            'vod_id': vid,
            'vod_name': d['vod_name'] or ('Video ' + vid),
            'vod_pic': d['vod_pic'],
            'vod_actor': '',
            'vod_remarks': d['vod_remarks'],
            'vod_year': '',
            'vod_area': '',
            'vod_content': d['vod_content'],
            'type_name': d['type_name'],
            'stills': d['stills'],
            'vod_play_from': 'KissJAV',
            'vod_play_url': play_url,
        }]}

    def searchContent(self, key, quick=False, pg=1):
        pg = _norm_pg(pg)
        if pg < 1:
            pg = 1
        kw = urllib.parse.quote(str(key).strip())
        url = '%s/search/?q=%s' % (BASE, kw) if pg == 1 else '%s/search/%s/%d/' % (BASE, kw, pg)
        body = _page(url)
        items = _parse_list(body)
        return {'list': items, 'page': pg, 'pagecount': _pagecount(body, RE_SEARCH_PAGE), 'total': 0}

    def playerContent(self, flag, id, vipFlags=None):
        raw = str(id or '')
        url = raw
        if '$' in raw:
            url = raw.split('$', 1)[1]
        if url.startswith('kissjav:'):
            vid = urllib.parse.unquote(url.split(':', 1)[1].strip()).lstrip('/')
            d = _detail(vid)
            stream = d.get('stream', '')
            if stream:
                return {'parse': 0, 'playUrl': '', 'url': stream,
                        'header': {'User-Agent': UA, 'Referer': d['detail_url']}}
            return {'parse': 0, 'playUrl': '', 'url': '', 'header': {}}
        if url.startswith('http'):
            return {'parse': 0, 'playUrl': '', 'url': url,
                    'header': {'User-Agent': UA, 'Referer': BASE + '/'}}
        return {'parse': 0, 'playUrl': '', 'url': '', 'header': {}}

    def localProxy(self, param):
        return [404, 'text/plain', b'', {}]

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return True

    def action(self, action, params=None):
        return {'code': 0, 'msg': 'ok'}
