# Runs on GitHub before the build: downloads the game's fonts and puts them inside the app, so it looks right with no internet.
import re, urllib.request, os
A='android/app/src/main/assets/'
html=open(A+'index.html',encoding='utf-8').read()
m=re.search(r'<link rel="stylesheet" href="(https://fonts.googleapis.com/[^"]+)">',html)
if m:
    req=urllib.request.Request(m.group(1).replace('&amp;','&'),headers={'User-Agent':'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36'})
    css=urllib.request.urlopen(req).read().decode()
    os.makedirs(A+'fonts',exist_ok=True)
    for i,u in enumerate(sorted(set(re.findall(r'url\((https://[^)]+)\)',css)))):
        fn='f%d.woff2'%i; open(A+'fonts/'+fn,'wb').write(urllib.request.urlopen(u).read()); css=css.replace(u,'fonts/'+fn)
    open(A+'fonts.css','w').write(css)
    html=html.replace(m.group(0),'<link rel="stylesheet" href="fonts.css">')
    open(A+'index.html','w',encoding='utf-8').write(html)
    print('fonts bundled')
