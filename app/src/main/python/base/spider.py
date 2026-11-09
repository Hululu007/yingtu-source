import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from importlib.machinery import SourceFileLoader

try:
    from com.chaquo.python import Python
    from com.github.catvod import Proxy
except Exception:
    Python = None
    Proxy = None


class _Response:
    def __init__(self, status_code, headers, content):
        self.status_code = status_code
        self.headers = headers
        self.content = content
        self.text = content.decode('utf-8', 'ignore')

    def json(self):
        return json.loads(self.text)


class BaseSpider:
    def __init__(self):
        self.extend = ''

    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        return {'class': [], 'filters': {}}

    def homeVideoContent(self):
        return {'list': []}

    def categoryContent(self, tid, pg, filter, extend):
        return {'page': 1, 'pagecount': 1, 'limit': 20, 'total': 0, 'list': []}

    def detailContent(self, ids):
        return {'list': []}

    def searchContent(self, key, quick, pg="1"):
        return {'list': []}

    def playerContent(self, flag, id, vipFlags):
        return {'parse': 0, 'url': '', 'header': {}}

    def liveContent(self, url):
        return ''

    def localProxy(self, param):
        return [404, 'text/plain', b'', {}]

    def isVideoFormat(self, url):
        return False

    def manualVideoCheck(self):
        return False

    def action(self, action):
        return {}

    def destroy(self):
        pass

    def getName(self):
        return ''

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        if Python is None:
            raise RuntimeError('Java bridge unavailable')
        cache_dir = Python.getPlatform().getApplication().getCacheDir().getAbsolutePath()
        path = os.path.join(os.path.join(cache_dir, 'py'), name + '.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        return m.group(group) if m else ''

    def removeHtmlTags(self, src):
        return re.sub('<.*?>', '', src)

    def cleanText(self, src):
        return re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)

    @staticmethod
    def str2json(s):
        return json.loads(s)

    @staticmethod
    def json2str(s):
        return json.dumps(s, ensure_ascii=False)

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=15, verify=True, stream=False, allow_redirects=True):
        return self._request('GET', url, params, None, None, cookies, headers, timeout, verify, allow_redirects)

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=15, verify=True, stream=False, allow_redirects=True):
        return self._request('POST', url, params, data, json, cookies, headers, timeout, verify, allow_redirects)

    def _request(self, method, url, params, data, payload, cookies, headers, timeout, verify, allow_redirects):
        if params:
            url = url + ('&' if '?' in url else '?') + urllib.parse.urlencode(params)
        body = None
        heads = dict(headers or {})
        if payload is not None:
            body = json.dumps(payload).encode('utf-8')
            heads.setdefault('Content-Type', 'application/json')
        elif data is not None:
            body = data if isinstance(data, bytes) else urllib.parse.urlencode(data).encode('utf-8')
        if cookies:
            heads['Cookie'] = '; '.join(k + '=' + v for k, v in cookies.items())
        req = urllib.request.Request(url, data=body, headers=heads, method=method)
        opener = urllib.request.build_opener(NoRedirectHandler()) if not allow_redirects else urllib.request.build_opener()
        for attempt in range(2):
            try:
                with opener.open(req, timeout=timeout) as rsp:
                    return _Response(rsp.status, dict(rsp.headers), rsp.read())
            except urllib.error.HTTPError as e:
                return _Response(e.code, dict(e.headers), e.read())
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
                if attempt == 0:
                    continue
                return _Response(0, {}, b'')
        return _Response(0, {}, b'')

    def html(self, content):
        return content

    def getProxyUrl(self, local=True):
        if Proxy is None:
            return ''
        return Proxy.getUrl(local) + '?do=py'

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(str(msg))


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


Spider = BaseSpider
