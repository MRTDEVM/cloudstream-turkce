import urllib.request, ssl, re
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
h=urllib.request.urlopen(urllib.request.Request('https://filmmakinesi.to/film/colony-2026-fmhs8q/',headers={'User-Agent':UA}),context=ctx,timeout=15).read().decode('utf-8','replace')
vids = re.findall(r'<a[^>]*data-video_url="([^"]+)"[^>]*>([\s\S]*?)</a>', h)
print('Videos with text:')
for url, text in vids:
    print('-', url, '->', text.strip())
