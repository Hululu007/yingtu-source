import json
import os
import urllib.request
from importlib.machinery import SourceFileLoader


def spider(cache, source, file_name=None):
    name = file_name or os.path.basename(source)
    path = cache + '/' + name
    download(path, source)
    name = name.split('.')[0]
    return SourceFileLoader(name, path).load_module().Spider()


def download(path, url):
    if url.startswith('http'):
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=30) as rsp:
            writeFile(path, rsp.read())
    else:
        writeFile(path, str.encode(url))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def str2json(content):
    return json.loads(content)


def dumps(result):
    return json.dumps(result, ensure_ascii=False)


def getDependence(ru):
    return ru.getDependence()


def getName(ru):
    return ru.getName()


def init(ru, extend):
    ru.init(extend)


def homeContent(ru, filter):
    return dumps(ru.homeContent(filter))


def homeVideoContent(ru):
    return dumps(ru.homeVideoContent())


def categoryContent(ru, tid, pg, filter, extend):
    return dumps(ru.categoryContent(tid, pg, filter, str2json(extend)))


def detailContent(ru, array):
    return dumps(ru.detailContent(str2json(array)))


def searchContent(ru, key, quick, pg="1"):
    return dumps(ru.searchContent(key, quick, pg))


def playerContent(ru, flag, id, vipFlags):
    return dumps(ru.playerContent(flag, id, str2json(vipFlags)))


def liveContent(ru, url):
    return ru.liveContent(url)


def localProxy(ru, param):
    return ru.localProxy(str2json(param))


def action(ru, action):
    return dumps(ru.action(action))


def destroy(ru):
    ru.destroy()
