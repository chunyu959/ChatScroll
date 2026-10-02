<p align="center">
  <img src="art/icon-source.png" width="128" alt="ChatScroll 图标"/>
</p>

<h1 align="center">ChatScroll · 聊卷</h1>

<p align="center">
  <strong>把 AI 聊天记录的 Markdown 导出文件，变成像聊天软件一样的阅读体验。</strong>
</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#支持的导入格式">格式</a> ·
  <a href="#下载安装">下载</a> ·
  <a href="#从源码构建">构建</a> ·
  <a href="./README.md">English</a>
</p>

---

ChatScroll（中文名：聊卷）是一款轻量、完全离线的 Android 应用，用来阅读以 Markdown 导出的 AI 对话记录——你的消息在右边，AI 的回复在左边，就像真正的聊天软件一样。

## 功能

- **聊天式阅读**——用户消息是暖色气泡靠右，AI 回复是干净的纯文本靠左，暖色的米白 + 陶土橙配色
- **完整 Markdown 渲染**——标题、列表、引用、表格、任务清单、链接、图片
- **代码块语法高亮**——Java、Kotlin、Python、JavaScript、JSON、SQL、YAML、Go、C/C++ 等
- **LaTeX 数学公式**——行内 `$...$` 与独立 `$$...$$`
- **思维链可折叠**——导出文件里附带的推理过程会被收起来，点开才显示
- **对话库**——每个导入的文件都会保存到列表里（标题、消息条数、导入时间）。还没打开过的会标上「新」，点进去就消失。长按可以重命名、置顶或删除，置顶的对话始终排在最前
- **重复导入会先问你**——内容一模一样的对话再次导入时会提示，而不是默默存成两份。判断依据是内容本身，不是文件名
- **两种导入方式**——在 App 内点 **+**，或在系统里用「分享」把 `.md` 文件直接发给 ChatScroll
- **中英双语**——自动跟随系统语言
- **完全离线**——不申请网络权限，无账号、无追踪

## 支持的导入格式

ChatScroll 识别使用 `User` / `Assistant` 角色标记的聊天记录导出文件：

```markdown
# 对话标题

**User**:

帮我看看这个问题？

---

**Assistant**:

好的，我的建议是……

- 第一点
- 第二点
```

- ✅ 支持 [RikkaHub](https://github.com/rikkahub/rikkahub) 导出格式（`**User**:` / `**Assistant**:` 角色标记，含导出的推理过程和内嵌图片）
- ✅ 其他任意 Markdown 文件——作为单个文档打开阅读
- 🔜 更多格式敬请期待

## 下载安装

前往 [Releases](../../releases) 下载最新 APK，安装到 Android 8.0 及以上的设备即可。

## 从源码构建

环境要求：JDK 17+，Android SDK（平台 36），用 Android Studio 打开即可直接运行。

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

如需自行签名 release 包，在项目根目录创建 `keystore.properties`（已被 gitignore）：

```properties
storeFile=keystore/chatscroll.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## 技术栈

Kotlin · Jetpack Compose (Material 3) · [Markwon](https://github.com/noties/Markwon)（Markdown 渲染）· [JLatexMath](https://github.com/opencollab/jlatexmath)（公式）· kotlinx-serialization（本地存储）

## 许可证

[MIT](./LICENSE) © 2026 ChatScroll Contributors
