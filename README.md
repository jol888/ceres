<div align="center">

# Ceres

**轻量、安静、为墨水屏而生的 Android 阅读器**

兼容 [Legado（阅读）](https://github.com/gedoor/legado) 书源 · 段评 · 发现页 · 本地书

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Legado](https://img.shields.io/badge/Legado-书源兼容-orange)
![License](https://img.shields.io/badge/license-AGPL--3.0-blue)

</div>

---

Ceres 只做一件事：把书读好。黑白界面、无动画翻页、按段落看评论；书从哪里来，由你导入的书源决定。

> [!NOTE]
> Ceres 不内置任何书籍内容。内容来自你自行导入的书源，版权归原作者及相应平台所有。请只导入你信任的书源，并遵守当地法律与网站条款。

## ✨ 特性

| | |
|---|---|
| 📚 **书源** | 导入 Legado 格式书源 JSON：文件、剪贴板、网络地址；一键导入 `legado://import/bookSource` 与 `yuedu://booksource` 链接，导入前会确认 |
| ⚙️ **规则引擎** | JSoup、XPath、JSONPath、正则与 Rhino JavaScript（ES6：箭头函数、模板字符串、`?.`、`??`）；URL 模板、POST、GBK、请求头、Cookie、`{{}}` / `@put` / `@get` 变量 |
| 🧩 **Legado 兼容** | 常用 `java.*` 函数（网络、编码、AES / DES / 3DES、哈希、zip、沙箱文件）、`source.*` 登录信息、`book.setReverseToc` 等 |
| 🌐 **网页能力** | 后台 WebView 取页（`webView:true`、`webJs`、`sourceRegex`）、应用内浏览器、验证码输入、Cookie 双向同步 |
| 🔑 **登录** | `loginUi` 表单（文本 / 密码 / 按钮 / 切换）、`login()` 脚本、网页登录自动保存 Cookie |
| 🧭 **发现** | `exploreUrl` 分类（文本、JSON、JS 三种写法）与 `ruleExplore` 书单，按页加载 |
| 💬 **段评** | 按段落显示评论数，评论分页与楼中回复（书源配置 `ruleReview` 时） |
| 📖 **阅读** | 分页排版、目录跳转、进度保存、章节预加载、音量键翻页、单手模式、墨水屏定期全刷 |
| 🗂️ **本地书** | TXT（自动识别编码与章节）与 EPUB |
| 🍅 **番茄** | 内置番茄原生客户端：设备注册、请求签名、正文解密与段评；搜索走公开接口，配套书源单独发布 |

## 🔍 兼容性

用 2560 个社区书源离线检查：

- 书源 JSON **全部**可解析
- 有 JS 语法问题的书源降到 **29** 个，剩下的在 Legado 中同样无法运行
- 1294 个不含 JS 的发现分类配置中 **1293** 个可解析

尚未支持：自定义字体替换（`queryTTF` / `replaceFont`）、登录过期检测（`loginCheckJs`）、签名与非对称加密（`createSign` / `createAsymmetricCrypto`）。

## 🍅 番茄支持

内置 `fanqie://` 原生客户端，处理番茄 App 的签名请求、正文解密与段评接口；搜索使用公开的 `novel.snssdk.com` 接口。详情、目录、正文与段评都由客户端完成，原版 Legado 无法直接使用。

配套书源单独维护：[jol888/fanqie-legado-source](https://github.com/jol888/fanqie-legado-source)。导入 `fanqie_novel_source.json` 即可搜索、阅读并查看段评。

首次阅读会自动注册设备身份并缓存正文密钥；密钥失效或解密失败时会自动重新注册。

> [!NOTE]
> 番茄相关实现仅用于个人学习与阅读便利，请遵守平台条款与当地法律。

## 🚀 构建

需要 JDK 17+ 与 Android SDK 37（`compileSdk = 37`，`minSdk = 26`）。

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 🧪 测试

```bash
# 单元测试（离线）
./gradlew :app:testDebugUnitTest

# 设备冒烟测试：Rhino on ART、后台 WebView、内置示例书源联网
./gradlew :app:connectedDebugAndroidTest

# 用社区书源合集做离线兼容性检查
CERES_COMPAT=/path/to/sources ./gradlew :app:testDebugUnitTest --tests '*CompatHarness*'

# 番茄端到端联调（真实网络，需指定书源 JSON）
CERES_LIVE=1 CERES_FANQIE_SOURCE=/path/to/fanqie_novel_source.json \
  ./gradlew :app:testDebugUnitTest --tests 'app.ceres.reader.source.fanqie.FanqieLiveTest'
```

## 🗺️ 目录结构

```
app/src/main/java/app/ceres/reader/
├── data/     书架、书源、缓存、本地导入（Room / DataStore）
├── rule/     规则引擎：URL、JSoup、XPath、JSONPath、正则、Rhino、Cookie
├── source/   书源执行（搜索 / 发现 / 详情 / 目录 / 正文）与段评
│   └── fanqie/  番茄原生客户端（设备、签名、正文、段评）
├── ui/       Compose 界面（书架、搜索、发现、详情、阅读器、书源、登录、设置）
└── web/      后台 WebView、应用内浏览器、验证码
```

## 📄 许可证

[GNU Affero General Public License v3.0](LICENSE)

## 🙏 致谢

- [Legado](https://github.com/gedoor/legado)：书源格式与规则语义的来源
- 月读 KOReader 插件（book.koplugin）：番茄设备注册与接口协议的重要参考
- OkHttp、Jsoup、JsoupXpath、Rhino、Jayway JsonPath、Gson、Room、Jetpack Compose 等开源项目
