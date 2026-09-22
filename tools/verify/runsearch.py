# -*- coding: utf-8 -*-
"""runsearch —— 搜索模板学习（表单反推 + 校准 URL 反推），v1.0.20 新增。"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402
JAVAC = _cp.javac()
JAVA = _cp.java()


def main():
    cp = _cp.classpath()
    out = os.path.join(HERE, '_out_runsearch')
    os.makedirs(out, exist_ok=True)
    src = os.path.join(HERE, 'SearchTpl.java')
    r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out, src],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print(r.stdout or '')
        print(r.stderr or '')
        print('==== SearchTpl COMPILE FAIL ====')
        return 1
    r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'SearchTpl'],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    print(r.stdout or '')
    if r.stderr:
        print(r.stderr[:600])
    return r.returncode


if __name__ == '__main__':
    sys.exit(main())
