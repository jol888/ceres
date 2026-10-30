# Ceres

<div align="center">

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Legado](https://img.shields.io/badge/Legado-书源兼容-orange)
![License](https://img.shields.io/badge/license-AGPL--3.0-blue)

</div>

一个轻量的 Android 阅读器，兼容 [Legado（阅读）](https://github.com/gedoor/legado) 书源格式：导入书源后可以搜索、发现、看详情、读目录与正文，并在支持的书源上显示段评。

> 内容来自用户自行导入的书源，版权归原作者及相应平台所有。请只导入你信任的书源，并遵守当地法律与网站条款。

## 功能

- **书源**：导入 Legado 格式书源 JSON（文件、剪贴板、网络地址），支持 `legado://import/bookSource` 与 `yuedu://booksource` 链接导入。
- **规则引擎**：JSONPath、XPath、JSoup、正则、Rhino JavaScript；支持 URL 模板与 `@js:`、POST、自定义请求头、Cookie、`{{}}` 变量、`@put` 变量。
- **网页能力**：内置 WebView 登录、验证码输入、`webView:true` 页面抓取与 Cookie 同步。
- **阅读**：分页排版、目录跳转、进度保存、章节预加载、音量键翻页、单手模式、墨水屏全局刷新。
- **段评**：按段落显示评论数，支持评论列表分页与回复（书源配置 `ruleReview` 时）。
- **本地书籍**：导入 TXT / EPUB，支持编码探测与 EPUB 目录解析。
- **其他**：书架、发现页、书源调试、离线缓存。

## 番茄小说支持

仓库内置 `fanqie://` 合成地址与番茄协议实现（Ceres 自己的 Kotlin 代码，不打包、不依赖番茄 App），能够处理番茄的签名请求、正文解密和段评接口。搜索使用公开的 `novel.snssdk.com` 接口；详情、目录、正文、段评由 Ceres 内的番茄模块完成。

配套书源单独发布：[jol888/fanqie-legado-source](https://github.com/jol888/fanqie-legado-source)。该源依赖本仓库的 `fanqie://` 实现，原版 Legado 无法直接使用。

首次阅读会自动注册设备身份并保存正文密钥；密钥过期或解密失败时会自动重新注册。

番茄相关实现仅用于个人学习与阅读便利，请遵守平台条款与当地法律。

## 构建

要求：JDK 17+、Android SDK 37（`compileSdk = 37`，`minSdk = 26`）。

```bash
./gradlew :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 测试

```bash
# 单元测试（不访问网络）
./gradlew :app:testDebugUnitTest

# 番茄端到端联调（真实网络，需先取得书源 JSON）
CERES_LIVE=1 CERES_FANQIE_SOURCE=/path/to/fanqie_novel_source.json \
  ./gradlew :app:testDebugUnitTest \
  --tests 'app.ceres.reader.source.fanqie.FanqieLiveTest'
```

联调用例默认跳过；找不到书源文件时也会跳过。它会依次检查 `CERES_FANQIE_SOURCE`、仓库根目录和相邻的 `fanqie-legado-source` 目录。

## 目录结构

```
app/src/main/java/app/ceres/reader/
├── data/          书架、书源、缓存、本地导入（Room / DataStore）
├── rule/          Legado 规则引擎：URL、JSONPath、XPath、JSoup、正则、Rhino、Cookie
├── source/        书源执行（搜索 / 详情 / 目录 / 正文）与段评加载
│   └── fanqie/    番茄协议实现（设备注册、签名、正文与段评）
├── ui/            Compose 界面（书架、搜索、发现、详情、阅读器、设置）
└── web/           WebView 登录、验证码、JS Host
```

## 许可证

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。

## 致谢

- [Legado](https://github.com/gedoor/legado)：书源格式与规则语义的来源。
- 月读 KOReader 插件（book.koplugin）：番茄设备注册与接口协议的重要参考。
- 以及 OkHttp、Jsoup、Rhino、Jayway JsonPath、Room、Compose 等开源项目。
