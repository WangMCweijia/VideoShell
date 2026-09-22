#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跑 ResumeKey.java：v1.0.18 的「播放进度记忆身份」离线校验。

外加一条**源码守卫**：确保 PlayerActivity 里不再出现按媒体地址 hashCode 记进度的写法，
并且确实用上了 episodeKey + Media.digest。守卫只看代码、先剥掉注释，
免得把解释性的文档注释误判成违规。
"""
import os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _cp  # noqa: E402

JAVAC = _cp.javac()
JAVA = _cp.java()
SRC = _cp.src('app/src/main/java/com/videoshell/player/PlayerActivity.kt')

kt = open(SRC, encoding='utf-8').read()
# 剥掉 /* ... */ 与 // ... 注释后再守卫（本文件没有把 // 写进字符串的情况）
code = re.sub(r'/\*[\s\S]*?\*/', '', kt)
code = re.sub(r'//[^\n]*', '', code)

violations = []
if 'hashCode()' in code:
    violations.append('PlayerActivity 仍在用 hashCode() 做进度 key')
if 'resume_${' in code:
    violations.append('PlayerActivity 仍存在字面量 resume_${...} 拼接的 key')
if 'episodeKey(' not in code:
    violations.append('PlayerActivity 缺少 episodeKey() —— 语义身份没落地')
if 'Media.digest' not in code:
    violations.append('PlayerActivity 未使用 Media.digest')
if 'currentEpKey' not in code:
    violations.append('PlayerActivity 缺少 currentEpKey 状态')

guard_fail = len(violations)
for v in violations:
    print('[FAIL] 源码守卫：' + v)
if not violations:
    print('[PASS] 源码守卫：进度 key = episodeKey(站|剧|集) + Media.digest，hashCode 写法已绝迹')

cp = _cp.classpath()
out = os.path.join(HERE, '_resumekey')
os.makedirs(out, exist_ok=True)

r = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', cp, '-d', out,
                    os.path.join(HERE, 'ResumeKey.java')],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
if r.returncode != 0:
    sys.stdout.write((r.stdout or '') + (r.stderr or ''))
    print('[FAIL] javac 编译 ResumeKey 失败')
    raise SystemExit(1)

r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-cp', out + os.pathsep + cp, 'ResumeKey'],
                   capture_output=True, text=True, encoding='utf-8', errors='replace')
txt = (r.stdout or '') + ('\n[stderr]\n' + r.stderr if r.stderr else '')
sys.stdout.write(txt)
open(os.path.join(HERE, '_resumekey.out.txt'), 'w', encoding='utf-8').write(txt)

rc = r.returncode
if guard_fail:
    rc = 1
sys.exit(rc if rc != 0 else 0)
