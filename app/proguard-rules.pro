# ============================================================================
# v1.0.57 起开启 R8 混淆 + 资源收缩（minifyEnabled/shrinkResources = true）。
# dex 占 APK 体积 83%（6.25MB / 7.53MB 压缩后），混淆是唯一有数量级收益的瘦身手段。
#
# ⚠️ 唯一的静默失效风险是 **Gson 反射序列化**：字段名就是 JSON 键，字段一被
#    混淆/裁剪，存进 SharedPreferences 的就是另一套键，读回来全是默认值 ——
#    症状是"配方丢了 / 历史丢了 / 站点列表空了"，且不崩溃。所以下面把所有被
#    Gson 摸过的类逐一 keep（清单来自对 fromJson/TypeToken 调用点的全量扫描，
#    v1.0.57 扫描结果就这六个；**新增 Gson 模型必须同步来这里登记**）。
# ============================================================================

# ---- Gson 模型（字段名 = JSON 键，不能混淆不能裁剪）----
-keep class com.videoshell.data.model.** { *; }          # SiteConfig / VideoItem / Category …
-keep class com.videoshell.data.site.SiteRecipe { *; }   # 配方（位置参数，RecipeTransfer 导入导出）
-keep class com.videoshell.data.site.CryptFamily$Rec { *; }  # 家族自证缓存
-keep class com.videoshell.data.ListCache$Entry { *; }   # 列表缓存
-keep class com.videoshell.data.HistEntry { *; }         # 历史记录
-keep class com.videoshell.data.FavEntry { *; }          # 收藏

# Gson 泛型解析靠签名信息（TypeToken 匿名子类）
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# ---- 直接引用、无需 keep，但要让 R8 闭嘴 ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# org.jsoup 旧规则里的 -keep 已删：它没有任何反射使用，keep 着白吃 ~0.4MB。
