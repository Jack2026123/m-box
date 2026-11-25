# -*- coding: utf-8 -*-
# 演示：基于 fmria.com 真实 HTML 侦察摘要写的 py 源
# 这就是 AI 看到真实网页结构后应该写出的源（和 fmria 源码逻辑一致）
import requests
from bs4 import BeautifulSoup
import re

xurl = "https://www.fmria.com"
headerx = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120',
    'Referer': 'https://www.fmria.com/'
}

def getList(html):
    videos = []
    soup = BeautifulSoup(html, "lxml")
    # 实测：列表项 class 是 module-items > module-item
    for box in soup.find_all('div', class_="module-items"):
        for item in box.find_all('div', class_="module-item"):
            vod = {}
            a = item.find('a', class_="module-item-pic")
            if not a:
                a = item.find('a')
            vod["vod_name"] = (a.get('title') or '').strip()
            vod["vod_id"] = a.get('href', '')
            img = item.find('img')
            vod["vod_pic"] = (img.get('data-src') or img.get('src') or '') if img else ''
            if vod["vod_pic"] and 'http' not in vod["vod_pic"]:
                vod["vod_pic"] = xurl + vod["vod_pic"]
            txt = item.find('div', class_="module-item-text")
            vod["vod_remarks"] = txt.text.strip() if txt else ''
            videos.append(vod)
    return videos


class Spider:
    def getName(self):
        return "韩剧网"

    def init(self, extend):
        pass

    def isVideoFormat(self, url):
        pass

    def homeContent(self, filter):
        return {"class": [
            {"type_id": "1", "type_name": "最新韩剧"},
            {"type_id": "2", "type_name": "韩国电影"},
            {"type_id": "3", "type_name": "韩国综艺"},
            {"type_id": "4", "type_name": "韩国动漫"}]}

    def homeVideoContent(self):
        resp = requests.get(url=xurl, headers=headerx).text
        return {'list': getList(resp)}

    def categoryContent(self, cid, pg, filter, ext):
        url = '%s/hjzx/%s-%s.html' % (xurl, cid, pg)
        resp = requests.get(url=url, headers=headerx).text
        result = {'list': getList(resp)}
        result['page'] = pg
        result['pagecount'] = 99
        result['limit'] = 90
        result['total'] = 99
        return result

    def detailContent(self, ids):
        did = ids[0]
        if 'http' not in did:
            did = xurl + did
        soup = BeautifulSoup(requests.get(url=did, headers=headerx).text, "lxml")
        vod = {"vod_id": did}
        vod["vod_name"] = soup.select_one('h1').get_text().strip()
        vod["vod_pic"] = ''
        pic = soup.select_one('.module-item-pic img')
        if pic:
            vod["vod_pic"] = pic.get('data-src', '') or pic.get('src', '')
        # 线路 tab 标题
        ktabs = []
        for XL in soup.select('.nav-tabs li a'):
            t = re.sub(r'\s+', '', XL.get_text())
            m = re.search(r'\((\d+)\)', t)
            if m:
                t = re.sub(r'\(\d+\)', '', t).strip() + '共' + m.group(1) + '集'
            ktabs.append(t)
        vod["vod_play_from"] = '$$$'.join(ktabs)
        # 播放列表（实测：#playlist1/#playlist2 区块内的 /play/{id}-{sid}-{nid}.html）
        klists = []
        for pid in ['playlist1', 'playlist2']:
            blk = re.search(r'id="' + pid + r'"(.*?)(?=id="playlist|$)', str(soup), re.S)
            if not blk:
                continue
            eps = re.findall(r'href="([^"]+)"[^>]*>([^<]+)<', blk.group(1))
            eps = [(u, t.strip()) for u, t in eps if t.strip()]
            klists.append('#'.join('%s$%s' % (t, u) for u, t in eps))
        vod["vod_play_url"] = '$$$'.join(klists)
        return {'list': [vod]}

    def playerContent(self, flag, id, vipFlags):
        try:
            html = requests.get(url=xurl + id, headers=headerx).text
            url = re.search(r'var\s+now\s*=\s*"([^"]+)"', html).group(1)
            return {"parse": 0, "playUrl": '', "url": url, "header": headerx}
        except Exception:
            return {"parse": 1, "playUrl": '', "url": xurl + id, "header": headerx}

    def searchContent(self, key, quick, page):
        # 实测：fmria 搜索路由是 /index.php/vod/search.html?wd={kw}
        import urllib.parse
        url = '%s/index.php/vod/search.html?wd=%s&page=%s' % (xurl, urllib.parse.quote(key), page)
        resp = requests.get(url=url, headers=headerx).text
        return {'list': getList(resp), 'page': page, 'pagecount': 60, 'limit': 30, 'total': 999999}


if __name__ == "__main__":
    import json
    spider = Spider()
    spider.init("")
    print("=== homeVideoContent ===")
    print(json.dumps(spider.homeVideoContent(), ensure_ascii=False)[:300])
    print("=== categoryContent ===")
    print(json.dumps(spider.categoryContent("1", "1", {}, {}), ensure_ascii=False)[:300])
    print("=== detailContent ===")
    print(json.dumps(spider.detailContent(["/hanjuzaixian/48.html"]), ensure_ascii=False)[:500])
    print("=== playerContent ===")
    d = spider.detailContent(["/hanjuzaixian/48.html"])["list"][0]
    ep0 = d["vod_play_url"].split("$$$")[0].split("#")[0]
    pid = ep0.split("$")[1]
    print(json.dumps(spider.playerContent("m3u8", pid, {}), ensure_ascii=False))
    print("=== searchContent ===")
    print(json.dumps(spider.searchContent("爱情", False, 1), ensure_ascii=False)[:300])