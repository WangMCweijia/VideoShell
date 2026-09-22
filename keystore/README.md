# 签名密钥不在这里

v1.0.54 之前，`videoshell.jks` 与 `keystore.properties` 是**被跟踪文件**，从根提交起
的 59 个提交全带着它。仓库转 public 前必须把它移出去，否则等于公开发布签名私钥 ——
私钥公开后，任何人都能签一个 `versionCode` 更大的同名包，而 Android 只认签名：
用户装了它，就等于把「更新」交到别人手里。**已泄露的那把钥匙视为作废。**

现在密钥有两个存放点，都不在仓库里：

| 场景 | 来源 | 位置 |
|---|---|---|
| 本机构建 | `keystore.properties`（已在 `.gitignore`，指向仓库外的 `.jks`） | `../releases/.keys/videoshell.jks` |
| CI 构建 | GitHub Secrets（`VS_KEYSTORE_B64` / `VS_STORE_PASSWORD` / `VS_KEY_ALIAS` / `VS_KEY_PASSWORD`） | 解到 `$RUNNER_TEMP/sign/` —— **刻意不放工作区**，否则会被 `upload-artifact` 打进产物 |

`app/build.gradle` 的签名配置**优先读环境变量**，其次才读本地 `keystore.properties`，
两条路都能出**真签名**的 release 包（debug 也走同一把钥匙 —— 否则 debug 与 release
互相覆盖安装会失败，症状是「我明明装了新版，界面还是老的」）。

⚠️ **换钥的代价**：签名不同的包**装不上**（`应用未安装：签名冲突`）。装过旧钥包的设备
必须**先卸载再安装**一次。
