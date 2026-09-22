# -*- coding: utf-8 -*-
"""R8 / 混淆配置守卫（v1.0.58）。

v1.0.57 事故（E30）：开 R8 后，`TypeToken` 匿名子类的**泛型签名**被剥掉
（`-keepattributes Signature` 只保证属性不被全局剥，保证不了"未被 keep 的
类"还带着属性），Gson 泛型解析全灭 ⇒ 站点列表读回为空、导入报
"没有可识别的站点"、添加后列表不出现 —— 三个症状一条根，且**不崩溃**。

这个套件锁三件事：
  1. minify/shrink 的开关状态被人知道地改动（不是不许动，是不许**悄悄**动）；
  2. Gson 模型 keep 清单 + TypeToken 子类 keep 必须在（丢了 = 数据静默清零）；
  3. 源码里**新出现的** Gson 反序列化目标类必须已在 keep 清单里
     （扫描 `::class.java` / `TypeToken` 调用点，对照清单，差一个就 FAIL）。

判据全部是**源码/配置文本**，纯离线。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PASS = FAIL = 0


def ok(what: str, cond: bool) -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {what}")
    else:
        FAIL += 1
        print(f"  [FAIL] {what}")


def main() -> int:
    gradle = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
    pro = (ROOT / "app" / "proguard-rules.pro").read_text(encoding="utf-8")

    print("== A. 开关状态（改动必须连注释一起改，不许悄悄动） ==")
    m_min = re.search(r"minifyEnabled\s+(\w+)", gradle)
    m_shr = re.search(r"shrinkResources\s+(\w+)", gradle)
    ok("minifyEnabled / shrinkResources 都在 release 块里", bool(m_min and m_shr))
    on = bool(m_min and m_min.group(1) == "true") and bool(m_shr and m_shr.group(1) == "true")
    ok("混淆当前是开的（v1.0.57 起瘦身 62% 靠它；要关必须连 PITFALLS 一起改）", on)

    print("== B. keep 清单（E30：丢了 = 数据静默清零，不崩溃） ==")
    ok("TypeToken 匿名子类整类 keep（泛型签名是 Gson 泛型解析的唯一类型来源）",
       "-keep class * extends com.google.gson.reflect.TypeToken" in pro)
    ok("TypeToken 本尊 keep（防合并/改名连坐）",
       "-keep class com.google.gson.reflect.TypeToken" in pro)
    for cls in [
        "com.videoshell.data.model.**",
        "com.videoshell.data.site.SiteRecipe",
        "com.videoshell.data.site.CryptFamily$Rec",
        "com.videoshell.data.ListCache$Entry",
        "com.videoshell.data.HistEntry",
        "com.videoshell.data.FavEntry",
    ]:
        ok(f"Gson 模型 keep 在位：{cls}", f"-keep class {cls}" in pro)
    ok("-keepattributes Signature 在（TypeToken 之外的泛型也要）",
       re.search(r"-keepattributes\s+[^\n]*Signature", pro) is not None)

    print("== C. 源码里的 Gson 调用点 ↔ keep 清单 对账 ==")
    src = ""
    for p in (ROOT / "app" / "src" / "main" / "java").rglob("*.kt"):
        src += p.read_text(encoding="utf-8")
    # fromJson(x, Y::class.java) 直呼类名的调用点
    direct = set(re.findall(r"fromJson[^\n]*?([A-Z]\w+(?:::\w+)?(?:\$\w+)?)::class\.java", src))
    keep_names = re.findall(r"-keep class ([\w.$*]+)", pro)
    keep_blob = " ".join(keep_names)

    known = {"SiteRecipe", "Rec"}  # 清单里已逐一登记的直呼目标
    missing = set()
    for d in direct:
        name = d.split("::")[0].split(".")[-1]
        if name in known:
            continue
        if name not in keep_blob:
            missing.add(name)
    ok(f"fromJson 直呼类名的目标（{len(direct)} 处）全部有 keep 依据{('：缺 ' + ','.join(sorted(missing))) if missing else ''}",
       not missing)

    # TypeToken 泛型里出现的模型类（`TypeToken<MutableList<X>>`）
    generics = set(re.findall(r"TypeToken<[^>]*?>[^\n]*?\(\)\s*\{\}", src))
    ok(f"TypeToken 调用点（{len(generics)} 处）被 B 段通配 keep 覆盖（* extends TypeToken）",
       bool(generics) and "-keep class * extends com.google.gson.reflect.TypeToken" in pro)

    print(f"\n================ pass={PASS} fail={FAIL} ================")
    if FAIL == 0 and PASS == 0:
        print("WEAK: 零断言 = 失败")
        return 1
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
