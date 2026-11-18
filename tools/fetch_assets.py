import urllib.request, re, os, time

base = 'https://cdn.jsdelivr.net/npm'
out = r'app/src/main/assets/vendor'
os.makedirs(out, exist_ok=True)
files = {
    'katex@0.16.21/dist/katex.min.js': 'katex.min.js',
    'katex@0.16.21/dist/contrib/auto-render.min.js': 'auto-render.min.js',
    'katex@0.16.21/dist/katex.min.css': 'katex.min.css',
}
for src, dst in files.items():
    path = os.path.join(out, dst)
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        print('skip', dst)
        continue
    for attempt in range(4):
        try:
            data = urllib.request.urlopen(f'{base}/{src}', timeout=90).read()
            open(path, 'wb').write(data)
            print(dst, len(data))
            break
        except Exception as e:
            print('retry', dst, attempt, e)
            time.sleep(2)
    else:
        raise SystemExit(1)
css = open(os.path.join(out, 'katex.min.css'), encoding='utf-8').read()
fonts = sorted(set(re.findall(r'fonts/([A-Za-z0-9_\-]+\.(?:woff2|woff|ttf))', css)))
os.makedirs(os.path.join(out, 'fonts'), exist_ok=True)
for f in fonts:
    path = os.path.join(out, 'fonts', f)
    if os.path.exists(path) and os.path.getsize(path) > 100:
        continue
    for attempt in range(4):
        try:
            data = urllib.request.urlopen(f'{base}/katex@0.16.21/dist/fonts/{f}', timeout=90).read()
            open(path, 'wb').write(data)
            break
        except Exception as e:
            print('retry font', f, attempt, e)
            time.sleep(2)
    else:
        raise SystemExit(2)
print('fonts', len(fonts), sum(os.path.getsize(os.path.join(out, 'fonts', f)) for f in fonts))