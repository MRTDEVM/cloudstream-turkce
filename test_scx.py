import urllib.request, ssl, re, json, base64
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
h=urllib.request.urlopen(urllib.request.Request('https://www.fullhdfilmizlesene.now/',headers={'User-Agent':UA}),context=ctx,timeout=15).read().decode('utf-8','replace')
links = re.findall(r'<a class="tt" href="([^"]+)">', h)
print('Links:', links[:3])
if links:
    h2=urllib.request.urlopen(urllib.request.Request(links[0],headers={'User-Agent':UA}),context=ctx,timeout=15).read().decode('utf-8','replace')
    scx = re.search(r'var\s+scx\s*=\s*(\{.*?\})\s*;', h2)
    if scx:
        data = json.loads(scx.group(1))
        for key, val in data.items():
            if type(val) is dict and 'tt' in val:
                tt = base64.b64decode(val['tt']).decode('utf-8')
                print('Source:', key, 'Name:', tt)
                print('Codes:', val.get('sx', {}).get('t'))
