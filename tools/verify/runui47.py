#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ui47 — v1.0.47 锁屏解锁键的两处修正（源码守卫）。

用户实测反馈两条：
  1) 锁定提示写「点左上角图标解锁」，但解锁键实际贴在**屏幕右侧正中** ⇒ 文案与布局对不上；
  2) 锁定后解锁键**一直常驻**在画面右侧，既碍眼又在那块位置留了个 44dp 误触区。

纯 UI 行为，离线造不出渲染态 ⇒ 全部证据是源码守卫：文案、布局 gravity、自动退场、
以及「锁定时单击仍能唤出解锁键」（这条最容易被后来的重构悄悄破坏 —— 锁定时
`onTouchEvent` 一旦回到 `return false`，单击就没人接了）。
"""
import io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _cp  # noqa: E402
ROOT = _cp.project_root()
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "videoshell")
RES = os.path.join(ROOT, "app", "src", "main", "res")

ok_n = fail_n = 0
def ok(name, cond):
    global ok_n, fail_n
    if cond: ok_n += 1; print("  [PASS] " + name)
    else:    fail_n += 1; print("  [FAIL] " + name)

def read(p):
    # 主文件 + 同主名的拆分子文件一起拼（见 _cp.kt）：守卫锁**代码文本**，不锁文件布局。
    # 否则把 god file 按职责拆开这种纯搬运会把断言判红（规则没丢，只是搬了家）。
    return _cp.kt(os.path.join(SRC, p))

def read_res(p):
    with io.open(os.path.join(RES, p), encoding="utf-8") as f:
        return f.read()

strings = read_res(os.path.join("values", "strings.xml"))
layout = read_res(os.path.join("layout", "activity_player.xml"))
pa = read(os.path.join("player", "PlayerActivity.kt"))
gl = read(os.path.join("player", "PlayerGestureLayout.kt"))

print("== A. 提示文案与解锁键位置必须一致 ==")
# 解锁键在布局里的真实位置：右侧垂直居中
i = layout.find('android:id="@+id/ivUnlock"')
seg = layout[i:i + 700] if i >= 0 else ""
ok("ivUnlock 确实贴在右侧垂直居中（layout_gravity=center_vertical|end）",
   'android:layout_gravity="center_vertical|end"' in seg)
i = strings.find('name="hud_locked"')
hud = strings[i:strings.find("</string>", i)] if i >= 0 else ""
ok("★ 文案不再说「左上角」（布局在右侧，说左上角就是错的）", "左上角" not in hud)
ok("★ 文案指向屏幕（解锁键会自己退场，得先点屏幕唤出）",
   ("点屏幕" in hud) or ("点一下屏幕" in hud))
ok("hud_locked 仍然存在（没被删掉）", i >= 0)

print("== B. 解锁键不再常驻：出现 → 停留 → 自动退场 ==")
ok("unlockStayMs 常量存在（停留时长集中一处）", "unlockStayMs" in pa)
# ⚠️ 源码判据**不带 `private ` 前缀**（v1.0.54 统一改过）。
#    原来写的是 "private fun xxx("，那是把「可见性修饰符」也钉进了判据 ——
#    而 god file 拆分时被搬到扩展文件里的函数一律变 "internal fun Owner.xxx("
#    （扩展函数访问不了 private），于是"功能一行没改、只是搬了家"也会判红。
#    判据要表达的是「这个签名的声明存在 / 这个函数体在这里」，可见性不是它要说的东西。
#    见 docs/PITFALLS.md §4.24 与 §4.41。
j = pa.find("val hideUnlock")
body = pa[j:pa.find("\n", j)] if j >= 0 else ""
ok("★ hideUnlock 只在 locked 时才收（解锁后不能被它误收）",
   "if (locked)" in body and "ivUnlock.visibility = View.GONE" in body)
j = pa.find("fun showUnlockBriefly()")
b1 = pa[j:pa.find("\n    }\n", j)] if j >= 0 else ""
ok("showUnlockBriefly() 存在", j >= 0)
ok("★ 它先取消旧任务再排队（否则连点会把退场时间冲乱）",
   "handler.removeCallbacks(hideUnlock)" in b1 and "handler.postDelayed(hideUnlock" in b1)
j = pa.find("fun toggleUnlockBriefly()")
b2 = pa[j:pa.find("\n    }\n", j)] if j >= 0 else ""
ok("toggleUnlockBriefly() 存在且在场则收起 / 不在场则唤出",
   j >= 0 and "showUnlockBriefly()" in b2 and "View.GONE" in b2)

j = pa.find("fun setLocked(")
b3 = pa[j:pa.find("\n    }\n", j)] if j >= 0 else ""
ok("★ setLocked(true) 走 showUnlockBriefly()，不再直接 VISIBLE 常驻",
   "showUnlockBriefly()" in b3)
ok("★ 旧的常驻写法已清除（回退守卫）",
   "binding.ivUnlock.visibility = if (v) View.VISIBLE else View.GONE" not in pa)
ok("setLocked(false) 收掉解锁键", "binding.ivUnlock.visibility = View.GONE" in b3)
ok("onDestroy 取消 hideUnlock（防 Activity 泄漏）",
   "handler.removeCallbacks(hideUnlock)" in pa[pa.find("override fun onDestroy()"):])

print("== C. 锁定时单击仍能唤出解锁键 ==")
ok("PlayerGestureLayout 暴露 onLockedTap", "var onLockedTap" in gl)
j = gl.find("override fun onSingleTapConfirmed")
b4 = gl[j:gl.find("\n        }\n", j)] if j >= 0 else ""
ok("★ 锁定时单击走 onLockedTap，而不是 onSingleTap（否则会去显隐控制条）",
   "if (locked) onLockedTap?.invoke() else onSingleTap?.invoke()" in b4)
j = gl.find("if (locked) {")
b5 = gl[j:gl.find("\n        }\n", j)] if j >= 0 else ""
ok("★ 锁定时不再直接 return false（那样单击没人接，只能干等按钮出现）",
   "if (locked) return false" not in gl)
ok("锁定时把事件喂给 tapDetector 并自己消费", "tapDetector.onTouchEvent(event)" in b5 and "return true" in b5)
ok("★ 锁定时不进入拖动分支（音量/亮度/快进依旧不响应）",
   "ACTION_MOVE" not in b5 and "MODE_H" not in b5)
j = gl.find("override fun onDoubleTap")
b6 = gl[j:gl.find("\n        }\n", j)] if j >= 0 else ""
ok("★ 锁定时双击不触发播放/暂停", "if (locked) return true" in b6)
j = gl.find("override fun onLongPress")
b7 = gl[j:gl.find("\n        }\n", j)] if j >= 0 else ""
ok("锁定时长按不触发倍速（v1.0.44 已有，此处防回退）", "if (locked) return" in b7)
ok("PlayerActivity 把 onLockedTap 接到 toggleUnlockBriefly()",
   "binding.gesture.onLockedTap = { toggleUnlockBriefly() }" in pa)

print("== D. 回归守卫（v1.0.44 悬浮锁屏键的成果不得回退）==")
ok("悬浮键 ivLock 仍跟控制条一起显隐",
   "binding.ivLock.visibility = if (visible && !locked) View.VISIBLE else View.GONE" in pa)
ok("锁定态仍在自动隐藏里豁免（autoHide 不吃锁定）",
   "if (!locked) setBarsVisible(false)" in pa)

print()
print("==== Ui47  pass=%d fail=%d ====" % (ok_n, fail_n))
sys.exit(1 if fail_n else 0)
