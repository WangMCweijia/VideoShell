import com.videoshell.data.site.SiteAdapter;

/**
 * 共享的「剥壳」判据：**谁在真正干活**？
 *
 * 为什么单独一个文件：这段判据原来在 3 个 harness 里各抄一遍（`Bs4` / `Live2` / `ZqFix`），
 * 而且三份都写死「只剥一层 [FamilyRouter]」。v1.0.53 最外层又加了一层 SeedRouter
 * ⇒ 三处**同时**假红，同一个原因抄三份 —— 正是 PITFALLS E7 的形状（"判据抄三份 = 三类各错一格"）。
 *
 * 现在的判据与"包了几层"无关：沿 `underlying` 一直剥到不再变为止。
 * 将来再加第 N 层路由器，这里不用改。
 */
public final class Chains {

    private Chains() {}

    /** 反射取 `underlying`（Kotlin `val underlying` ⇒ getUnderlying）；没有这个方法就返回 null */
    public static Object underlyingOf(Object o) {
        try {
            return o.getClass().getMethod("getUnderlying").invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 剥壳并回答"剥到第几跳才命中 kind"；命中不了返回 -1。
     *
     * 兼作**非空断言的守卫**：上层可以要求 `hops(...) >= 1`，含义不只是"最终是 HtmlAdapter"，
     * 而是"**确实经过了路由器那一层**" —— 否则将来有人把路由层从链路里摘掉，断言照样绿（vacuous）。
     */
    public static int hops(SiteAdapter a, Class<?> kind) {
        Object cur = a;
        for (int hop = 0; hop < 8; hop++) {      // 上限 8：防呆，防 `underlying` 指回自己打转
            if (kind.isInstance(cur)) return hop;
            Object next = underlyingOf(cur);
            if (next == null || next == cur || next.getClass() == cur.getClass()) return -1;
            cur = next;
        }
        return -1;
    }

    public static boolean worksAs(SiteAdapter a, Class<?> kind) {
        return hops(a, kind) >= 0;
    }

    /** 剥到底之后的那个对象；纯给**诊断文案**用（"SeedRouter → HtmlAdapter" 里的右边） */
    public static Object effective(Object a) {
        Object cur = a;
        for (int i = 0; i < 8; i++) {
            Object next = underlyingOf(cur);
            if (next == null || next == cur || next.getClass() == cur.getClass()) return cur;
            cur = next;
        }
        return cur;
    }
}
