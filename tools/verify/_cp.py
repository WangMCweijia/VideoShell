#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线 harness 的公共**路径 / 工具解析中心**（跨平台，v1.0.53 迁入仓库时建立）。

为什么要有它：harness 原来是"私人工作区"里的脚本，工具链路径全写死成
`C:\\Program Files\\Microsoft\\jdk-...` / `D:\\TRAE\\视频壳` —— 那样的东西**进不了 CI**
（runner 是 Linux，JDK 在 `JAVA_HOME`、SDK 在 `ANDROID_HOME`、gradle 缓存在 `~/.gradle`）。

现在的纪律：**harness 里不许再出现本机绝对路径**，需要什么就找这里要 ——

| 要什么 | 调什么 |
|---|---|
| 工程根（含 `settings.gradle` 的那层） | `project_root()` |
| JDK 可执行 | `javac()` / `java()` / `javap()` |
| gradle 模块缓存 | `gradle_cache()` |
| Android SDK 的桩 `android.jar` | `android_jar()`（找不到返回 `None`） |
| `apksigner` | `apksigner()` |
| 离线样本目录 | `samples('_bs')` |
| 工程内某个文件 | `src('app/src/main/java/...')` |
| harness 用的 classpath | `classpath()` |

每一项的解析顺序都是「**环境变量 → 常规位置 → 从本文件位置推断**」，
所以同一份 harness 在 Windows 开发机与 Linux CI 上都能跑。

⚠️ `android.jar` 是**桩**，`android.net.Uri.parse()` 之类在 JVM 上会抛 "Stub!"。
它只为"类加载不崩"而挂 —— see `classpath()`。
"""
import glob, io, os, shutil, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
_M3JARS = os.path.join(HERE, '_m3jars')


def _first_existing(*paths):
    for p in paths:
        if p and os.path.exists(p):
            return p
    return None


def project_root():
    """工程根（含 `settings.gradle` 的那一层）。

    顺序：`VS_ROOT`/`VIDEOSHELL_ROOT` 环境变量 → 本文件上两级（`<root>/tools/verify/`）
    → 从 cwd 往上找 `settings.gradle` → 旧位置（harness 还在仓库外时）。
    """
    env = os.environ.get('VS_ROOT') or os.environ.get('VIDEOSHELL_ROOT')
    if env and os.path.isdir(env):
        return os.path.abspath(env)
    for base in (os.path.abspath(os.path.join(HERE, '..', '..')), os.path.abspath(os.getcwd())):
        d = base
        for _ in range(6):
            if os.path.exists(os.path.join(d, 'settings.gradle')) or \
               os.path.exists(os.path.join(d, 'settings.gradle.kts')):
                return d
            nd = os.path.dirname(d)
            if nd == d:
                break
            d = nd
    # 旧位置：<root>/../releases/.tools/videoshell_verify
    return os.path.abspath(os.path.join(HERE, '..', '..', '..'))


# ---------------------------------------------------------------- JDK
def _jdk_home():
    for k in ('JAVA_HOME', 'JDK_HOME'):
        v = os.environ.get(k)
        if v and os.path.isdir(v):
            return v
    exe = shutil.which('javac')
    if exe:
        return os.path.dirname(os.path.dirname(os.path.abspath(exe)))
    return None


def _bin(name):
    home = _jdk_home()
    if home:
        for cand in (os.path.join(home, 'bin', name),
                     os.path.join(home, 'bin', name + '.exe')):
            if os.path.exists(cand):
                return cand
    got = shutil.which(name)
    if got:
        return got
    raise SystemExit('找不到 %s —— 请设置 JAVA_HOME，或把它加进 PATH' % name)


def javac():
    return _bin('javac')


def java():
    return _bin('java')


def javap():
    return _bin('javap')


# ---------------------------------------------------------------- gradle / SDK
def gradle_cache():
    """gradle 的模块缓存（`modules-2/files-2.1`）。"""
    home = os.environ.get('GRADLE_USER_HOME')
    cands = []
    if home:
        cands.append(os.path.join(home, 'caches', 'modules-2', 'files-2.1'))
    cands.append(os.path.join(os.path.expanduser('~'), '.gradle',
                              'caches', 'modules-2', 'files-2.1'))
    return _first_existing(*cands) or cands[-1]


def _sdk_root():
    for k in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK'):
        v = os.environ.get(k)
        if v and os.path.isdir(v):
            return v
    lp = os.path.join(project_root(), 'local.properties')
    if os.path.exists(lp):
        try:
            for ln in io.open(lp, encoding='utf-8', errors='replace'):
                if ln.strip().startswith('sdk.dir'):
                    raw = ln.split('=', 1)[1].strip()
                    # Windows 的 local.properties 会写成 D:\\Android\\Sdk
                    return raw.replace('\\\\', '\\').replace('\\:', ':')
        except Exception:
            pass
    return None


def android_jar(platform='android-34'):
    """桩 jar；找不到返回 `None`（调用方要判空，别直接 `os.path.exists(None)`）。"""
    sdk = _sdk_root()
    if sdk:
        p = os.path.join(sdk, 'platforms', platform, 'android.jar')
        if os.path.exists(p):
            return p
    return None


def apksigner():
    """SDK build-tools 里的 apksigner（Windows 是 `.bat`）。找不到返回 `None`。"""
    sdk = _sdk_root()
    if sdk:
        bt = os.path.join(sdk, 'build-tools')
        if os.path.isdir(bt):
            for v in sorted(os.listdir(bt), reverse=True):
                for n in ('apksigner.bat', 'apksigner'):
                    p = os.path.join(bt, v, n)
                    if os.path.exists(p):
                        return p
    return shutil.which('apksigner')


def m3jars():
    """从 media3 的 aar 里抽出来的 classes.jar 的存放目录。"""
    return _M3JARS


# ---------------------------------------------------------------- 样本
def samples(name):
    """离线样本目录。

    同名样本有两份是**故意**的：开发机上样本放在工程根的 `_bs/`（存量、不提交），
    CI 里用入仓的 `tools/verify/samples/bs/`。优先用前者，这样本地跑的是"最新抓的那份"。
    """
    name = name.strip().lstrip('./')
    cand = os.path.join(project_root(), name)
    if os.path.isdir(cand):
        return cand
    alt = os.path.join(HERE, 'samples', name.lstrip('_'))
    if os.path.isdir(alt):
        return alt
    return cand          # 都不在：返回首选路径，让下游报"缺样本"时能看到期望位置


def src(rel):
    """工程内文件，`rel` 用正斜杠写（如 `app/src/main/java/.../PlayerActivity.kt`）。"""
    return os.path.join(project_root(), *rel.split('/'))


def kt(path):
    """读 Kotlin 源码 = **主文件 + 同主名的拆分子文件**（按文件名排序拼接）。

    为什么要这样：源码守卫是用 `read(路径)` 拿到文本后做
    `contains` / `body_of(...)` / `count(...) == N` 断言的 —— 它锁的是**代码文本**，
    但路径本身把它绑死在了**文件布局**上。于是"把 god file 按职责拆成几个文件"
    这种纯搬运（一行逻辑都没改）会把断言判红：不是规则丢了，只是规则搬了家。
    逐条去改断言的路径更糟 —— 下次再拆又是满地红，且守卫从此只敢锁布局不敢锁行为。

    把"某个类的全部源码"定义成 主文件 + `<主名>_*.kt`，搬运就与守卫无关了。
    三条纪律：
      1. 只对 `.kt` 生效（xml / properties 原样返回）；
      2. 拼接**不复制**任何内容 —— 所以 `count(x) == 1`（"可见性赋值只此一处"）
         这类计数断言的语义完全不变（内容仍只出现一次）；
      3. 子文件里的顶层 `internal` 声明要**还原成类成员写法**（见 _unwrap）——
         否则判据里写的 `private fun xxx(` 会因为接收者前缀（`internal fun Owner.xxx(`）
         而对不上，症状是"函数明明在、断言却说没有"。见 PITFALLS §4.41。
    """
    try:
        text = io.open(path, encoding='utf-8').read()
    except (IOError, OSError):
        text = None
    if text is None or not path.endswith('.kt'):
        return text
    d = os.path.dirname(path)
    base = os.path.basename(path)[:-3]
    pre = base + '_'
    try:
        names = sorted(n for n in os.listdir(d)
                       if n.startswith(pre) and n.endswith('.kt'))
    except OSError:
        names = []
    for n in names:
        try:
            sub = io.open(os.path.join(d, n), encoding='utf-8').read()
        except (IOError, OSError):
            continue
        text += '\n' + '\n'.join(_unwrap(l, base) for l in sub.split('\n'))
    return text


def _unwrap(line, owner):
    """把拆出去的子文件里的顶层 internal 声明还原成类成员的写法。

    拆分后子文件里是 `internal fun Owner.xxx(` / `internal val xxx = …`
    （扩展函数访问不了 private，被它读到的字段也要放宽），而守卫的判据写的是拆分前的
    样子 `private fun xxx(`。两者是**一一对应的同一段代码**，差别只在可见性修饰符与
    接收者前缀 —— 那是"搬到哪、谁能看见"，不是"代码做了什么"。

    所以按行做恒等改写，让聚合文本与拆分前逐行等价。只在**子文件**上做；1:1 行替换，
    不复制也不删除 ⇒ 真被删掉的代码不会因为改写而"看起来还在"。
    """
    if not line.startswith('internal '):
        return line
    rest = line[len('internal '):]
    for mod in ('fun ', 'suspend fun '):
        p = mod + owner + '.'
        if rest.startswith(p):
            return 'private ' + mod + rest[len(p):]
    if rest.startswith('val '):
        return 'private val ' + rest[4:]
    if rest.startswith('var '):
        return 'private var ' + rest[4:]
    return line


# ---------------------------------------------------------------- classpath
JAR_PATTERNS = [
    'org.jetbrains.kotlin/kotlin-stdlib/1.9.22/*/kotlin-stdlib-1.9.22.jar',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.7.3/*/kotlinx-coroutines-core-jvm-1.7.3.jar',
    'com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar',
    'com.squareup.okio/okio-jvm/3.6.0/*/okio-jvm-3.6.0.jar',
    'org.jsoup/jsoup/1.17.1/*/jsoup-1.17.1.jar',
    'com.google.guava/guava/31.1-jre/*/guava-31.1-jre.jar',
    'com.google.guava/failureaccess/1.0.1/*/failureaccess-1.0.1.jar',
    'com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar',
    'androidx.annotation/annotation/1.7.1/*/annotation-1.7.1.jar',
    'androidx.annotation/annotation-jvm/1.7.1/*/annotation-jvm-1.7.1.jar',
    'androidx.collection/collection/1.2.0/*/collection-1.2.0.jar',
    'androidx.collection/collection-jvm/1.4.0/*/collection-jvm-1.4.0.jar',
]


def _extract_media3():
    """把 gradle 缓存里的 media3 aar 里的 classes.jar 抽出来备用。

    为什么需要：`SiteDoctor` 会调播放器那套 DataSource（`OkHttpDataSource` /
    `HlsFixDataSource`），于是**任何**调 SiteDoctor 的 harness 都会去加载 media3 的类，
    classpath 里没有就 `NoClassDefFoundError`。
    """
    os.makedirs(_M3JARS, exist_ok=True)
    pat = os.path.join(gradle_cache(), 'androidx.media3', '**', '*.aar')
    for aar in glob.glob(pat, recursive=True):
        dst = os.path.join(_M3JARS, os.path.basename(aar)[:-4] + '.jar')
        if os.path.exists(dst):
            continue
        try:
            with zipfile.ZipFile(aar) as z, z.open('classes.jar') as f:
                open(dst, 'wb').write(f.read())
        except Exception:
            pass


def classpath(extra=None):
    _extract_media3()
    root = project_root()
    classes = os.path.join(root, 'app', 'build', 'tmp', 'kotlin-classes', 'debug')
    if not os.path.isdir(classes):
        raise SystemExit('先编译：./gradlew :app:assembleDebug')
    libs = [classes]
    # AGP 生成的 R.jar。**必须挂**：只要被断言碰到的 Kotlin 文件里出现
    # `R.string.xxx`（例如枚举自己带文案），类初始化就会 NoClassDefFoundError，
    # 而报错信息指向的是那个枚举，不是"少挂了 R" —— 很容易被误读成代码问题。
    rjar = os.path.join(root, 'app', 'build', 'intermediates',
                        'compile_and_runtime_not_namespaced_r_class_jar', 'debug', 'R.jar')
    if os.path.exists(rjar):
        libs.append(rjar)
    cache = gradle_cache()
    for pat in JAR_PATTERNS:
        for h in sorted(glob.glob(os.path.join(cache, pat))):
            libs.append(h)
    libs += sorted(glob.glob(os.path.join(_M3JARS, '*.jar')))
    aj = android_jar()
    if aj:
        libs.append(aj)
    if extra:
        libs += list(extra)
    return os.pathsep.join(libs)


if __name__ == '__main__':
    print('project_root  =', project_root())
    print('javac         =', javac())
    print('java          =', java())
    print('gradle_cache  =', gradle_cache())
    print('android_jar   =', android_jar())
    print('apksigner     =', apksigner())
    print('samples(_bs)  =', samples('_bs'))
