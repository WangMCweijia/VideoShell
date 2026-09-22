# tools/refactor —— god file「纯搬运」拆分工具

把某个上千行的 Kotlin 类的若干区段**原样搬**成同包的「扩展函数文件」，让改动某段逻辑时
不必在 1700 行里穿越四段互不相干的代码。**搬运动作一行逻辑都不改** —— 否则它就从
"重构"变成了"改动"，而改动需要重新证明（见 `docs/PITFALLS.md` §4.31 的 A/B 定位法）。

## 三个脚本

| 脚本 | 作用 | 用法 |
|---|---|---|
| `outline.py` | 列出一个 .kt 里**每个成员的边界行号**（含前置 KDoc 起始行），用来选干净的切分区间 | `python tools/refactor/outline.py HtmlExtractor.kt` |
| `split_god_file.py` | 按 spec 切区间 → 去一层缩进 → 改写成 `internal fun Owner.xxx(` → 删原区间 → 自检 | `python tools/refactor/split_god_file.py spec.json` |
| `fix_visibility.py` | 编译 → 自动修「可见性 / 限定名」类错误 → 再编译，直到收敛 | `python tools/refactor/fix_visibility.py HtmlExtractor` |

`repo_root.py` 是三者共用的工程根定位（`VS_ROOT` 环境变量 → 向上找 `settings.gradle`）。
**路径不写死** —— 否则入仓只是换个地方放垃圾。
（它**特意不叫** `_root.py`：根 `.gitignore` 有一条 `_*.py` 用来屏蔽根目录的临时脚本，
共享模块用下划线开头会被**静默**排除在提交之外 —— `git status` 干净、字段却没进仓。）

## spec.json 的形态

```json
{
  "target": "HtmlAdapter.kt",
  "owner":  "HtmlAdapter",
  "groups": [
    {"suffix": "Cats",   "zones": [[584, 765]], "header": ["…"]},
    {"suffix": "Browse", "zones": [[882, 958], [1227, 1266]], "header": ["…"]}
  ]
}
```

行号全部是**动手前**的原始行号（脚本一次算完再统一删，不必按从后往前写）。
每个 `group` 的 `zones` 可以多段，段与段之间夹着的东西会**留在原类**（例如被夹在中间的
`onBackPressed` —— 它是 `override`，本来也必须留下）。

## 五条硬约束（每条都踩过，两条是**静默**的）

1. **`override fun` 一个都不能搬** —— 扩展函数不能覆盖成员。脚本会在区间里发现它时打印出来。
2. **`suspend` 必须原样带走** —— 丢了它等于把函数改成阻塞语义，症状是成串的
   "Suspend function should be called only from a coroutine"。
3. **类内 private 字段名撞上父类属性 = 静默失效** —— 搬出类后编译器**不报**"访问不到"，
   而是静默改用父类那个（`SniffActivity.title` 撞 `Activity.getTitle(): CharSequence!`）。
   `fix_visibility.py` 的 `static_widen()` 在**编译前**就按引用放宽，专门治这类。
4. **`this@Owner` 在扩展函数里不成立** —— 隐式接收者的标签是**函数名**，须写成 `this@函数名`；
   在 `lifecycleScope.launch {}` 里**不能**写成 `this`（那已经是协程作用域了）。
5. **嵌套类型不自动进入别的作用域** —— `Pick` / `Card` / `LineBlock` 写进签名后必须
   `import` 或写限定名；**companion 成员 import 不了**，类外只能写 `Owner.MODE_XXX`。

## 为什么不会把守卫搞红

守卫读的是**代码文本**：`_cp.kt()` 把 `Owner.kt` 与 `Owner_*.kt` **拼接**后交给断言，
`_unwrap()` 把 `internal fun Owner.x(` 恒等还原成 `private fun x(` —— 于是聚合文本与拆分前
**逐行等价**，`count(x) == N` 这类计数断言语义不变。

代价是**两份 `agg()`/`unwrap()` 必须一起改**：Python 侧在 `tools/verify/_cp.py`，
Java 侧在 8 个守卫文件里各自实现（`Agg37/38/39`、`CalibMerge`、`EpName`、`Family`、
`YgCalib`、`YgImage`）。见 `docs/PITFALLS.md` §4.41。

拆完**必跑** `python tools/verify/runall.py`，并对 Java 侧那批守卫单独确认（它们直接调用
Kotlin 成员 —— 被 Java 调用的方法**必须留在原类里**，判据：
`grep -rn "[.]<方法名>(" tools/verify/*.java`）。
