import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.videoshell.data.model.Category;
import com.videoshell.data.model.Episode;
import com.videoshell.data.model.VideoItem;
import com.videoshell.data.site.AesCipher;
import com.videoshell.data.site.Base64Lite;
import com.videoshell.data.site.CryptRecipes;
import com.videoshell.data.site.CryptRecipe;
import com.videoshell.data.site.PseudoPlayUrl;
import com.videoshell.data.site.YeguoMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ③ 加密接口白名单（野果）的离线断言。
 *
 * 全部数据来自 `_ygo/*.json` —— 那是**服务端真实响应**（含 AES 密文）。
 * AES-CBC 在固定 key/iv 下是确定性的，所以这些密文可以在无网络、无真机的情况下解一遍：
 * 解不开就说明 key/iv/模式被改坏了，解开了就继续断言字段映射。
 */
public class Ygo {

    static final String KEY = "2acf7e91e9864673";
    static final String IV = "1c29882d3ddfcfd6";
    // 夹具目录：runygo.py 会用 args[0] 覆盖；默认值只为"在本机直接跑"兜底
    static String FIX = "_ygo/";

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = (want == null) ? got == null : want.equals(got);
        ok(name + "  (want=" + want + " got=" + got + ")", c);
    }

    static String read(String f) throws Exception {
        return new String(Files.readAllBytes(Paths.get(FIX + f)), StandardCharsets.UTF_8);
    }

    /** 取夹具的原始密文 */
    static String cipherOf(String f) throws Exception {
        return JsonParser.parseString(read(f)).getAsJsonObject().get("data").getAsString();
    }

    /** 解夹具 -> 解密后的顶层 JSON（形如 {data:..., status:1, msg:"ok"}） */
    static JsonObject decryptFixture(String f) throws Exception {
        String plain = AesCipher.INSTANCE.decrypt(cipherOf(f), KEY, IV, "CBC", "Pkcs7");
        if (plain == null) throw new IllegalStateException("解密失败: " + f);
        return JsonParser.parseString(plain).getAsJsonObject();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            FIX = args[0].endsWith("/") || args[0].endsWith("\\") ? args[0] : args[0] + "/";
        }
        System.out.println("=== A. Base64Lite ===");
        ok("空串 -> null", Base64Lite.INSTANCE.decode("") == null);
        ok("非字母表 -> null", Base64Lite.INSTANCE.decode("!!!!") == null);
        eq("YQ== -> a", "a", new String(Base64Lite.INSTANCE.decode("YQ=="), StandardCharsets.UTF_8));
        eq("YWI= -> ab", "ab", new String(Base64Lite.INSTANCE.decode("YWI="), StandardCharsets.UTF_8));
        eq("YWJj -> abc", "abc", new String(Base64Lite.INSTANCE.decode("YWJj"), StandardCharsets.UTF_8));
        eq("带换行/空格也认", "abc",
                new String(Base64Lite.INSTANCE.decode(" YW\nJj \n"), StandardCharsets.UTF_8));
        eq("缺省填充也认", "ab", new String(Base64Lite.INSTANCE.decode("YWI"), StandardCharsets.UTF_8));
        // URL-safe 字母表：-/_ 应与 +/ 等价
        byte[] plus = Base64Lite.INSTANCE.decode("+/8=");
        byte[] urlSafe = Base64Lite.INSTANCE.decode("-_8=");
        ok("URL-safe(-_) 与标准(+/ )等价",
                plus != null && urlSafe != null && java.util.Arrays.equals(plus, urlSafe));

        System.out.println();
        System.out.println("=== B. AesCipher.bytesOf / restorePlus ===");
        byte[] k = AesCipher.INSTANCE.bytesOf(KEY);
        eq("含字母字面量 -> 16 字节", 16, k.length);
        eq("取的是 ASCII（首字节 0x32）", (byte) 0x32, k[0]);
        eq("getBytes 一致", new String(k, StandardCharsets.UTF_8), KEY);
        // 纯数字+下划线 = 字符码表分支：50_97_99 -> "2ac"
        eq("字符码表分支 50_97_99 -> 2ac", "2ac",
                new String(AesCipher.INSTANCE.bytesOf("50_97_99"), StandardCharsets.UTF_8));
        // 纯数字无下划线 = 走恒等分支
        eq("纯数字无下划线 -> 原样", "50123",
                new String(AesCipher.INSTANCE.bytesOf("50123"), StandardCharsets.UTF_8));
        eq("restorePlus（空格还原成 +）", "a+b+c+", AesCipher.INSTANCE.restorePlus("a b c+"));

        System.out.println();
        System.out.println("=== C. 解密负例（不能静默出错） ===");
        String good = cipherOf("search.json");
        // ★ 正向断言必须先有：只测负例的话，"永远返回 null"也能全绿（这个 bug 真发生过）
        String pos = AesCipher.INSTANCE.decrypt(good, KEY, IV, "CBC", "Pkcs7");
        ok("正确 key/iv 能解出明文（正向）", pos != null && pos.contains("\"status\":1"));
        ok("换错 key -> null", AesCipher.INSTANCE.decrypt(good, "0000000000000000", IV, "CBC", "Pkcs7") == null);
        // 换错 IV **不能**期望 null：CBC 只坏第一个块，末尾填充仍合法，
        // 所以解出来是"首块乱码、其余正确"的东西。这里断言的是"解不出原文"——
        // 这正是它真实的安全性质，也说明**不能靠 null 判断 IV 对不对**。
        String wrongIv = AesCipher.INSTANCE.decrypt(good, KEY, "0000000000000000", "CBC", "Pkcs7");
        ok("换错 iv -> 解不出原文（CBC 只坏首块，故按「不等」判定）",
                wrongIv == null || !wrongIv.equals(pos));
        ok("非 base64 -> null", AesCipher.INSTANCE.decrypt("!!!!not_b64", KEY, IV, "CBC", "Pkcs7") == null);
        ok("空密文 -> null", AesCipher.INSTANCE.decrypt("", KEY, IV, "CBC", "Pkcs7") == null);
        try {
            String trunc = good.substring(0, good.length() / 2);
            String r = AesCipher.INSTANCE.decrypt(trunc, KEY, IV, "CBC", "Pkcs7");
            ok("截断密文不抛异常（返回 " + (r == null ? "null" : r.length() + "字") + "）", true);
        } catch (Throwable t) {
            ok("截断密文不抛异常（抛了 " + t.getClass().getSimpleName() + "）", false);
        }

        System.out.println();
        System.out.println("=== D. 真实夹具解密（全部接口） ===");
        String[] fx = {"search.json", "search_tag.json", "search_duanju.json", "search_actor.json",
                "detail.json", "play.json", "taglist.json",
                "contentoptions.json", "explorelist.json", "videorank.json"};
        JsonObject search = null, searchTag = null, searchDuanju = null, searchActor = null,
                detail = null, play = null, taglist = null, options = null, explore = null, rank = null;
        for (String f : fx) {
            JsonObject o = null;
            try { o = decryptFixture(f); } catch (Throwable t) { }
            ok("解密 " + f, o != null);
            String st = (o != null && o.get("status") != null) ? o.get("status").getAsString() : "";
            eq("  " + f + " status==1", "1", st);
            if ("search.json".equals(f)) search = o;
            if ("search_tag.json".equals(f)) searchTag = o;
            if ("search_duanju.json".equals(f)) searchDuanju = o;
            if ("search_actor.json".equals(f)) searchActor = o;
            if ("detail.json".equals(f)) detail = o;
            if ("play.json".equals(f)) play = o;
            if ("taglist.json".equals(f)) taglist = o;
            if ("contentoptions.json".equals(f)) options = o;
            if ("explorelist.json".equals(f)) explore = o;
            if ("videorank.json".equals(f)) rank = o;
        }

        System.out.println();
        System.out.println("=== E. 列表映射 ===");
        List<VideoItem> s1 = YeguoMap.INSTANCE.itemsFromResp(search);
        eq("搜索「爱」条数", 20, s1.size());
        ok("首条有 id/name/pic",
                !s1.isEmpty() && !s1.get(0).getId().isEmpty()
                        && !s1.get(0).getName().isEmpty() && !s1.get(0).getPic().isEmpty());
        ok("搜索命中关键词（首条含「爱」）", s1.get(0).getName().contains("爱"));
        boolean allFull = !s1.isEmpty();
        for (VideoItem v : s1) {
            if (v.getId().isEmpty() || v.getName().isEmpty() || v.getPic().isEmpty()) allFull = false;
        }
        ok("搜索每条都有 id/name/pic（无占位卡）", allFull);
        eq("taglist(都市) 条数", 20, YeguoMap.INSTANCE.itemsFromResp(taglist).size());
        eq("exploreList 条数", 20, YeguoMap.INSTANCE.itemsFromResp(explore).size());
        eq("videoRank 条数", 20, YeguoMap.INSTANCE.itemsFromResp(rank).size());
        eq("itemsFromResp(null) 空表", 0, YeguoMap.INSTANCE.itemsFromResp(null).size());
        eq("itemsFrom(null) 空表", 0, YeguoMap.INSTANCE.itemsFrom(null).size());
        // rank 里首条 title 应为「少妇白洁」（夹具抓取时的实际内容）
        eq("videoRank 首条标题", "少妇白洁", YeguoMap.INSTANCE.itemsFromResp(rank).get(0).getName());

        System.out.println();
        System.out.println("=== F. 分类栏（contentOptions） ===");
        List<Category> cats = YeguoMap.INSTANCE.categoriesFrom(options);
        ok("分类数 >= 25（实测 2 + 26）", cats.size() >= 25);
        Set<String> ids = new HashSet<>();
        for (Category c : cats) ids.add(c.getId());
        ok("含虚拟 tab @latest 最新", ids.contains("@latest"));
        ok("含虚拟 tab @rank 热播", ids.contains("@rank"));
        ok("含 tag:都市", ids.contains("tag:都市"));
        ok("含 tag:奇幻", ids.contains("tag:奇幻"));
        ok("含 tag:重生", ids.contains("tag:重生"));
        ok("含 tag:校园（背景维度）", ids.contains("tag:校园"));
        ok("排除占位项「全部」", !ids.contains("tag:全部"));
        ok("排除占位项「默认」", !ids.contains("tag:默认"));
        Set<String> uniq = new HashSet<>();
        boolean dup = false;
        for (Category c : cats) if (!uniq.add(c.getId())) dup = true;
        ok("分类 id 无重复", !dup);
        eq("categoriesFrom(null) 只剩 2 个虚拟 tab", 2, YeguoMap.INSTANCE.categoriesFrom(null).size());
        eq("首项是「最新」", "最新", cats.get(0).getName());
        eq("次项是「热播」", "热播", cats.get(1).getName());

        System.out.println();
        System.out.println("=== G. 详情 / 分集 ===");
        JsonObject dd = detail.getAsJsonObject("data");
        JsonArray detEps = dd.getAsJsonArray("episodes");
        eq("detail.episodes 条数", 22, detEps.size());
        Map<String, String> titles = YeguoMap.INSTANCE.titleMapFrom(toList(detEps));
        eq("titleMap 条数", 22, titles.size());
        JsonObject pd = play.getAsJsonObject("data");
        List<Episode> eps = YeguoMap.INSTANCE.episodesFrom("2275", pd, toList(detEps), titles);
        eq("episodesFrom 条数", 22, eps.size());
        eq("首集地址是伪地址", "yeguo://play/2275/186514", eps.get(0).getUrl());
        boolean namesOk = !eps.isEmpty();
        for (Episode e : eps) if (e.getName() == null || e.getName().trim().isEmpty()) namesOk = false;
        ok("每集都有集名", namesOk);
        Set<String> urlSet = new HashSet<>();
        boolean urlDup = false;
        for (Episode e : eps) if (!urlSet.add(e.getUrl())) urlDup = true;
        ok("分集地址互不相同（带 episodeId）", !urlDup);
        // episodeAll 缺失时回落到 detail.episodes[]
        eq("episodeAll 缺失时回落 detail.episodes", 22,
                YeguoMap.INSTANCE.episodesFrom("2275", null, toList(detEps), titles).size());
        eq("两者都缺失 -> 空表", 0,
                YeguoMap.INSTANCE.episodesFrom("2275", null, new ArrayList<JsonElement>(),
                        new java.util.HashMap<String, String>()).size());

        System.out.println();
        System.out.println("=== H. 真链提取 / 伪地址 ===");
        String url = YeguoMap.INSTANCE.streamUrlOf(pd, "186514");
        ok("streamUrlOf 命中 m3u8", url.contains(".m3u8"));
        ok("streamUrlOf 带 auth_key 时效签名（不可固化）", url.contains("auth_key="));
        eq("streamUrlOf(null) 空串", "", YeguoMap.INSTANCE.streamUrlOf(null, "1"));
        eq("streamUrlOf 不存在的集 -> 顶层仍命中", url, YeguoMap.INSTANCE.streamUrlOf(pd, "999999"));
        ok("isPseudo 伪地址", PseudoPlayUrl.INSTANCE.isPseudo("yeguo://play/1/2"));
        ok("isPseudo http 地址为假", !PseudoPlayUrl.INSTANCE.isPseudo("https://a/b.m3u8"));
        eq("伪地址 round-trip videoId", "2275",
                PseudoPlayUrl.INSTANCE.parse(PseudoPlayUrl.INSTANCE.build("2275", "186514")).getFirst());
        eq("伪地址 round-trip episodeId", "186514",
                PseudoPlayUrl.INSTANCE.parse(PseudoPlayUrl.INSTANCE.build("2275", "186514")).getSecond());
        ok("parse(http) -> null", PseudoPlayUrl.INSTANCE.parse("https://a/b.m3u8") == null);
        ok("parse(残缺伪地址) -> null", PseudoPlayUrl.INSTANCE.parse("yeguo://play/1") == null);

        System.out.println();
        System.out.println("=== J. 演员 tab 的形态（结论：不用它） ===");
        // 站点搜索有 tab=actor，但**实测剧集 tab 已是宽匹配**（标题 + 标签 + 演员名：
        // 搜「木君」=演员名 返回 20 条，搜「AI短剧」=标签 也返回 20 条），
        // 而演员项没有 title、映射不进本壳的列表模型 ⇒ 壳里只用剧集 tab。
        // 保留夹具与这几条断言，是为了站点改版（比如剧集 tab 收窄成只搜标题）时能第一时间发现。
        JsonObject aData = searchActor == null ? null : searchActor.getAsJsonObject("data");
        JsonArray aList = (aData == null) ? null : aData.getAsJsonArray("list");
        ok("演员 tab 仍返回 data.list", aList != null && aList.size() > 0);
        ok("演员项没有 title 字段（与剧集项结构不同，不能直接互用）",
                aList != null && aList.size() > 0
                        && !aList.get(0).getAsJsonObject().has("title"));
        eq("演员项用 representative_work 指代表作", "乡村爱情之骚妇秀云",
                aList == null || aList.size() == 0 ? ""
                        : aList.get(0).getAsJsonObject().get("representative_work").getAsString());
        eq("演员项用 representative_video_id 指可播影片", "2372",
                aList == null || aList.size() == 0 ? ""
                        : aList.get(0).getAsJsonObject().get("representative_video_id").getAsString());

        System.out.println();
        System.out.println("=== I. 白名单匹配 ===");
        ok("www.yeguodj.com 命中", CryptRecipes.INSTANCE.forHost("www.yeguodj.com") != null);
        ok("裸域 yeguodj.com 命中", CryptRecipes.INSTANCE.forHost("yeguodj.com") != null);
        ok("子域 staff-ygdj.yeguodj.com 命中",
                CryptRecipes.INSTANCE.forHost("staff-ygdj.yeguodj.com") != null);
        ok("备用域名 ygdj2.com 命中", CryptRecipes.INSTANCE.forUrl("https://ygdj2.com/") != null);
        ok("ygdj3.com 命中", CryptRecipes.INSTANCE.forUrl("https://ygdj3.com/x") != null);
        ok("百度不命中", CryptRecipes.INSTANCE.forHost("baidu.com") == null);
        ok("空主机名不命中", CryptRecipes.INSTANCE.forHost("") == null);
        ok("相似域名不误命中（yeguodj.com.evil.net）",
                CryptRecipes.INSTANCE.forHost("yeguodj.com.evil.net") == null);
        CryptRecipe r = CryptRecipes.INSTANCE.forHost("www.yeguodj.com");
        eq("配方 apiBase", "https://www.yeguodj.com/api.php", r.getApiBase());
        eq("配方 keySpec", KEY, r.getKeySpec());
        eq("配方 ivSpec", IV, r.getIvSpec());
        eq("配方 mode", "CBC", r.getMode());
        eq("配方 padding", "Pkcs7", r.getPadding());

        System.out.println();
        System.out.println("=== K. 搜索命中来源（宽匹配为什么「看起来结果错」） ===");
        // 站点搜索是**宽匹配**：标题、标签、演员、简介都算。搜标签词时返回的片名可以
        // 一个都不含关键词 —— 用户只能看到片名，于是判「结果错的」。站点在每一项里
        // 下发了 matched_fields，我们把它翻成角标，让结果自己解释自己。
        List<VideoItem> kTag = YeguoMap.INSTANCE.searchItemsFromResp(searchTag);
        List<VideoItem> kDuan = YeguoMap.INSTANCE.searchItemsFromResp(searchDuanju);
        List<VideoItem> kAi = YeguoMap.INSTANCE.searchItemsFromResp(search);

        eq("「甜宠」条数", 9, kTag.size());
        eq("「甜宠」标题命中条数（实测 0 —— 全靠标签/简介）", 0, countTitleHit(kTag, "甜宠"));
        eq("「甜宠」带角标条数", 9, countHinted(kTag));
        eq("「甜宠」首条角标", "命中标签", kTag.get(0).getRemarks());
        eq("「甜宠」靠简介命中的条数", 2, countRemark(kTag, "命中简介"));

        eq("「短剧」条数", 20, kDuan.size());
        eq("「短剧」标题命中条数（实测 0 —— 全是 AI短剧 的子串）", 0, countTitleHit(kDuan, "短剧"));
        eq("「短剧」带角标条数", 20, countHinted(kDuan));
        eq("「短剧」角标全为「命中标签」", 20, countRemark(kDuan, "命中标签"));

        eq("「爱」条数", 20, kAi.size());
        eq("「爱」标题命中条数（实测 17）", 17, countTitleHit(kAi, "爱"));
        eq("「爱」只有非标题命中才带角标", 3, countHinted(kAi));
        boolean titleNeverHinted = true;
        for (VideoItem v : kAi) {
            if (v.getName().contains("爱") && !v.getRemarks().isEmpty()) titleNeverHinted = false;
        }
        ok("标题命中的条目不标角标（关键词就在片名里，标了只占地方）", titleNeverHinted);

        // 角标位原本就是空的：站点在搜索接口不下发 play_count_text
        // ⇒ 这条改动是**纯增量**，不覆盖站点给的任何信息
        boolean rawBlank = true;
        for (VideoItem v : YeguoMap.INSTANCE.itemsFromResp(searchTag)) {
            if (v.getRemarks() != null && !v.getRemarks().isEmpty()) rawBlank = false;
        }
        ok("原映射的角标位确实为空（新角标不覆盖站点信息）", rawBlank);

        // 只加角标，不增删、不重排
        List<VideoItem> rawDuan = YeguoMap.INSTANCE.itemsFromResp(searchDuanju);
        eq("加角标前后条数一致", rawDuan.size(), kDuan.size());
        boolean sameOrder = rawDuan.size() == kDuan.size();
        for (int i = 0; sameOrder && i < rawDuan.size(); i++) {
            if (!rawDuan.get(i).getId().equals(kDuan.get(i).getId())) sameOrder = false;
        }
        ok("加角标前后 id 序列一致（不改顺序）", sameOrder);

        // matchHintOf 的边界（不依赖夹具）
        eq("null -> 空", "", YeguoMap.INSTANCE.matchHintOf(null));
        eq("无 matched_fields 键 -> 空", "", YeguoMap.INSTANCE.matchHintOf(mf()));
        eq("matched_fields 为空数组 -> 空", "", YeguoMap.INSTANCE.matchHintOf(mfEmptyArray()));
        eq("title -> 空", "", YeguoMap.INSTANCE.matchHintOf(mf("title")));
        eq("title+description -> 空（标题命中优先，不标）", "",
                YeguoMap.INSTANCE.matchHintOf(mf("title", "description")));
        eq("tags -> 命中标签", "命中标签", YeguoMap.INSTANCE.matchHintOf(mf("tags")));
        eq("actors -> 命中演员", "命中演员", YeguoMap.INSTANCE.matchHintOf(mf("actors")));
        eq("description -> 命中简介", "命中简介", YeguoMap.INSTANCE.matchHintOf(mf("description")));
        eq("tags+actors -> 取第一个", "命中标签",
                YeguoMap.INSTANCE.matchHintOf(mf("tags", "actors")));
        eq("未知字段 -> 关键词相关（宁可含糊，不可静默）", "关键词相关",
                YeguoMap.INSTANCE.matchHintOf(mf("director")));
        eq("空串字段 -> 空", "", YeguoMap.INSTANCE.matchHintOf(mf("")));

        System.out.println();
        System.out.println("==== runygo  pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }

    /** 造一个只带 matched_fields 的对象；不传参 = 该键缺失 */
    static JsonObject mf(String... fields) {
        JsonObject o = new JsonObject();
        if (fields != null && fields.length > 0) {
            JsonArray a = new JsonArray();
            for (String f : fields) a.add(f);
            o.add("matched_fields", a);
        }
        return o;
    }

    static JsonObject mfEmptyArray() {
        JsonObject o = new JsonObject();
        o.add("matched_fields", new JsonArray());
        return o;
    }

    static int countTitleHit(List<VideoItem> l, String kw) {
        int n = 0;
        for (VideoItem v : l) if (v.getName().contains(kw)) n++;
        return n;
    }

    static int countHinted(List<VideoItem> l) {
        int n = 0;
        for (VideoItem v : l) if (v.getRemarks() != null && v.getRemarks().startsWith("命中")) n++;
        return n;
    }

    static int countRemark(List<VideoItem> l, String r) {
        int n = 0;
        for (VideoItem v : l) if (r.equals(v.getRemarks())) n++;
        return n;
    }

    static List<JsonElement> toList(JsonArray a) {
        List<JsonElement> l = new ArrayList<>();
        for (JsonElement e : a) l.add(e);
        return l;
    }
}
