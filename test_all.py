import urllib.request, ssl, re, json
ctx = ssl.create_default_context(); ctx.check_hostname=False; ctx.verify_mode=ssl.CERT_NONE
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
def f(u,r=None,js=False):
    h={'User-Agent':UA}
    if r: h['Referer']=r
    if js: h['X-Requested-With']='fetch'; h['Accept']='application/json'
    return urllib.request.urlopen(urllib.request.Request(u,headers=h),context=ctx,timeout=15).read().decode('utf-8','replace')

# Find the AJAX search endpoint in main.js
js = f('https://www.hdfilmcehennemi.nl/dist/js/main.js?hash=d93042c1c9')
# Look for /search pattern
idx = js.find("'/search")
if idx < 0: idx = js.find('"/search')
if idx >= 0:
    print("Search context:", js[idx:idx+300])

# Also try the /search endpoint with fetch headers
print('\n=== TESTING /search AJAX ===')
for q in ['avatar']:
    for path in ['/search?q='+q, '/search/'+q, '/search?query='+q, '/search/q/'+q]:
        url = 'https://www.hdfilmcehennemi.nl'+path
        try:
            resp = f(url, r='https://www.hdfilmcehennemi.nl/', js=True)
            print(path, '->', len(resp), 'bytes')
            print('  Content:', resp[:300])
        except Exception as e:
            print(path, '->', e)
