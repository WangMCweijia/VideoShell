import os, subprocess, zipfile, glob

import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
CACHE = os.path.join(_cp.gradle_cache(), 'androidx.media3')
JAVAP = _cp.javap()
WORK = _cp.m3jars()
os.makedirs(WORK, exist_ok=True)

aars = {}
for d in os.listdir(CACHE):
    for f in glob.glob(os.path.join(CACHE, d, '**', '*.aar'), recursive=True):
        aars[os.path.basename(f)[:-4]] = f

jars = []
for name in ['media3-datasource-1.2.1', 'media3-common-1.2.1']:
    src = aars.get(name)
    if not src:
        continue
    dst = os.path.join(WORK, name + '.jar')
    if not os.path.exists(dst):
        with zipfile.ZipFile(src) as z:
            with z.open('classes.jar') as f, open(dst, 'wb') as o:
                o.write(f.read())
    jars.append(dst)
cp = os.pathsep.join(jars)

targets = [
    'androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException',
    'androidx.media3.datasource.HttpDataSource$HttpDataSourceException',
    'androidx.media3.datasource.BaseDataSource',
    'androidx.media3.datasource.DataSpec',
    'androidx.media3.datasource.HttpDataSource',
]
for t in targets:
    print('=' * 70)
    print(t)
    r = subprocess.run([JAVAP, '-cp', cp, t], capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    print(r.stdout or r.stderr)
