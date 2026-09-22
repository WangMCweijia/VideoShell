# -*- coding: utf-8 -*-
"""dex 签名片段守卫（v1.0.58，E30 的防复发闸·构建产物层）。

背景：v1.0.57 首开 R8，gson 2.10.1 的 jar 里**没有** consumer 规则（2.11 才有），
`TypeToken` 匿名子类的 Signature 注解被 R8 整体剥掉 ⇒ Gson 泛型解析全灭 ⇒
站点列表读回为空 / 导入报"没有可识别的站点" / 添加后列表不出现 —— 三症一根，
且**不崩溃**（E30）。

⚠️ 判据写法上的坑（V1.0.58 当天就踩过）：DEX 把 Signature 注解的值**拆成多个
字符串片段**存（`Lcom/google/gson/reflect/TypeToken<`、`Ljava/util/List<`、
`>;` 各一段），拿完整签名串去 dex 里搜**永远 GONE** —— 会把好的误判成坏的。
必须按片段判。本套件就是片段级断言：

  A.（构建产物层，需先 assembleDebug + assembleRelease）
     release dex 里必须能找到 TypeToken 签名片段 + 各 Gson 模型类描述符
     + 关键字段名。v1.0.57 的包实测第一组就是缺的（A/B 对照确证）。
  B.（源码层，永远可跑）
     proguard-rules.pro 的 TypeToken keep 规则在 —— APK 缺席时它兜底，
     保证本套件不会以"零断言"伪装成过。
"""
import re
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PASS = FAIL = SKIP = 0


def ok(what: str, cond: bool) -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {what}")
    else:
        FAIL += 1
        print(f"  [FAIL] {what}")


def skip(what: str) -> None:
    global SKIP
    SKIP += 1
    print(f"  [SKIP] {what}")


def dex_strings(dex: bytes) -> set:
    (tag,) = struct.unpack_from("<I", dex, 0)
    if tag != 0x0A786564:  # 'dex\n' 小端
        raise ValueError(f"not a dex: {tag:#x}")
    n, off = struct.unpack_from("<II", dex, 56)  # string_ids_size/off
    out = set()
    for i in range(n):
        (doff,) = struct.unpack_from("<I", dex, off + 4 * i)
        p, shift, ln = doff, 0, 0
        while True:
            b = dex[p]
            p += 1
            ln |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        out.add(dex[p:p + ln].decode("utf-8", "replace"))
    return out


def apk_strings(apk: Path) -> set:
    z = zipfile.ZipFile(apk)
    out = set()
    for n in z.namelist():
        if n.endswith(".dex"):
            out |= dex_strings(z.read(n))
    return out


def main() -> int:
    pro = (ROOT / "app" / "proguard-rules.pro").read_text(encoding="utf-8")
    print("== B. 源码层（永远可跑；APK 缺席时的兜底） ==")
    ok("TypeToken 匿名子类 keep 规则在 proguard-rules.pro（E30 根修）",
       "-keep class * extends com.google.gson.reflect.TypeToken" in pro)

    dbg_apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    rel_apk = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

    if not dbg_apk.exists() or not rel_apk.exists():
        skip("构建产物 APK 缺席（先跑 tools/build.py :app:assembleDebug + assembleRelease）"
             "—— A 段整体跳过")
    else:
        print("== A. 构建产物层：release dex 的签名片段 / 模型描述符 / 字段名 ==")
        rel = apk_strings(rel_apk)
        dbg = apk_strings(dbg_apk)
        # --- 片段级：Signature 注解被拆成多段存，必须按片段判 ---
        for frag, why in [
            ("Lcom/google/gson/reflect/TypeToken<",
             "TypeToken 子类签名片段 —— v1.0.57 实测就是缺它导致 Gson 全灭"),
            ("Ljava/util/List<", "List 泛型片段"),
            ("Ljava/util/Map<", "Map 泛型片段（播放页 headers）"),
        ]:
            ok(f"release dex 含签名片段 {frag}（{why}）", frag in rel)
        # --- 类描述符（= JSON 数据模型，被 keep 后名字不变） ---
        for cls in [
            "Lcom/videoshell/data/model/SiteConfig;",
            "Lcom/videoshell/data/HistEntry;",
            "Lcom/videoshell/data/FavEntry;",
            "Lcom/videoshell/data/ListCache$Entry;",
            "Lcom/videoshell/data/site/SiteRecipe;",
            "Lcom/videoshell/data/site/CryptFamily$Rec;",
        ]:
            ok(f"release dex 含模型类 {cls}", cls in rel)
        # --- 字段名（= JSON 键） ---
        for f in ["calibAt", "baseUrl", "apiMode", "updatedAt", "detailTpl"]:
            ok(f"release dex 含字段名 {f}", f in rel)
        # --- 对照：这些片段在 debug（未混淆）里都该在；release 不许比 debug 少关键项 ---
        for frag in ["Lcom/google/gson/reflect/TypeToken<", "Ljava/util/List<"]:
            if frag in dbg:
                ok(f"debug 有 {frag} ⇒ release 也必须有（A/B 对照）", frag in rel)

    print(f"\n================ pass={PASS} fail={FAIL} skip={SKIP} ================")
    if FAIL == 0 and PASS == 0:
        print("WEAK: 零断言 = 失败")
        return 1
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
