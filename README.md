<p align="center">
  <img src="art/icon-source.png" width="128" alt="ChatScroll icon"/>
</p>

<h1 align="center">ChatScroll</h1>

<p align="center">
  <strong>Turn exported AI chat transcripts into a comfortable, chat-style reading experience.</strong><br/>
  把 AI 聊天记录的 Markdown 导出文件，变成像聊天软件一样的阅读体验。
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#supported-import-formats">Formats</a> ·
  <a href="#download">Download</a> ·
  <a href="#build-from-source">Build</a> ·
  <a href="./README.zh-CN.md">简体中文</a>
</p>

---

ChatScroll (Chinese: 聊卷) is a lightweight, fully offline Android app for reading conversation transcripts exported as Markdown — with your messages on the right and the assistant on the left, just like a real chat app.

## Features

- **Chat-style reading** — user messages in warm bubbles on the right, assistant replies rendered as clean plain text on the left, Claude-inspired ivory & terracotta theme
- **Full Markdown rendering** — headings, lists, quotes, tables, task lists, links, images
- **Syntax-highlighted code blocks** — Java, Kotlin, Python, JavaScript, JSON, SQL, YAML, Go, C/C++ and more
- **LaTeX math** — inline `$...$` and block `$$...$$` formulas
- **Conversation library** — every imported file is saved in a list with title, message count and import date; long-press to delete
- **Two ways to import** — tap **+** inside the app, or use the system *Share* menu to send a `.md` file straight to ChatScroll
- **Bilingual UI** — English & 简体中文, follows the system language automatically
- **100% offline** — no network permission, no accounts, no tracking

## Supported import formats

ChatScroll recognizes chat transcripts that use `User` / `Assistant` role markers:

```markdown
# Conversation title

**User**:

Hey, could you help me with something?

---

**Assistant**:

Sure! Here's what I found…

- point one
- point two
```

- ✅ Chat exports using `**User**:` / `**Assistant**:` headers (e.g. [RikkaHub](https://github.com/rikkahub/rikkahub) conversation exports)
- ✅ Any other Markdown file — opens as a single readable document
- 🔜 More import formats from other apps are planned

> ChatScroll is an independent open-source project and is not affiliated with RikkaHub or any AI vendor. All trademarks belong to their respective owners.

## Download

Grab the latest APK from [Releases](../../releases) and install it on any Android 8.0+ device.

## Build from source

Requirements: JDK 17+, Android SDK with platform 36 (Android Studio works out of the box).

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

To sign release builds yourself, create `keystore.properties` in the project root (gitignored):

```properties
storeFile=keystore/chatscroll.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Tech stack

Kotlin · Jetpack Compose (Material 3) · [Markwon](https://github.com/noties/Markwon) for Markdown rendering · [JLatexMath](https://github.com/opencollab/jlatexmath) for LaTeX · kotlinx-serialization for local storage

## License

[MIT](./LICENSE) © 2026 ChatScroll Contributors
