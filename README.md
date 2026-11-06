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

- **Chat-style reading** — user messages in warm bubbles on the right, assistant replies rendered as clean plain text on the left, a warm ivory and terracotta theme
- **Full Markdown rendering** — headings, lists, quotes, tables, task lists, links, images
- **Syntax-highlighted code blocks** — Java, Kotlin, Python, JavaScript, JSON, SQL, YAML, Go, C/C++ and more
- **LaTeX math** — inline `$...$` and block `$$...$$` formulas
- **Collapsible thinking** — chain-of-thought exported as leading quotes is folded away, and stays collapsed until you tap it
- **Conversation library** — every imported file is saved in a list with title, message count and import date. Unread ones are marked *New* until you open them. Long-press to rename, pin, delete, or delete several at once; pinned conversations stay on top
- **Folders and ordering** — all/unclassified/custom single-level folders with create, rename, delete and batch move; import time, natural display-name, and original Markdown size ordering in both directions, with pinned entries first and remembered choices
- **Duplicate check** — importing a conversation whose content is identical to one you already have asks first, instead of silently creating a second copy. The file name is not what decides it
- **Two ways to import** — tap **+** inside the app, or use the system *Share* menu to send a `.md` file straight to ChatScroll
- **Bilingual UI** — English & 简体中文, follows the system language automatically
- **Reader controls** — separate body/all/code copy, selectable text, message directory, bookmarks, top/bottom navigation, expand/collapse all, fullscreen image zoom, and optional quiet reading without numbered toolbars
- **Wide tables** — readable columns with horizontal scrolling and fullscreen viewing; reference/HTML contexts remain intact
- **History and storage** — preview and restore the previous parsing result while retaining the current version; inspect categorized usage and confirm cleanup of validated older history only
- **Search and typography** — retained library searches open the matching message; reading search highlights matches; adjustable font/line spacing and light/dark/system themes
- **Originals and backup** — new imports retain their original Markdown; inspect/export it or parse again with an explicit format. Portable ZIP backup/restore merges without overwriting existing entries
- **Reliable local storage** — atomic checkpoints, recovery copies, full-content duplicate fingerprints, separate image assets and search indexes; imports queue while the process remains alive
- **Offline** — no network permission, accounts, or tracking. Android system backup is enabled and follows device settings; manual backups contain private conversation data and should be stored accordingly

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

- ✅ Supports older and newer [RikkaHub](https://github.com/rikkahub/rikkahub) role-header exports, including reasoning, embedded images, fenced tool inputs/results, truncated tool previews, and images inside tool results. Tool blocks end at their output fence, preserving the reply between calls
- ✅ Supports the [Kelivo](https://github.com/Chevey339/kelivo) export format (each message carries a time and a speaker; reasoning and tool calls are folded by default)
- ✅ Supports the [Chatbox](https://chatboxai.app) export format (`**user**:` / `**assistant**:` headers with the text in a code block; tool calls are folded by default). Chatbox exports don’t include images or the model’s reasoning
- ✅ Any other Markdown file — opens as a single readable document
- 🔜 More formats coming soon

RikkaHub uses the same `>` syntax for reasoning and ordinary quotations. Quotes at the start of an assistant message or immediately after a tool are folded automatically; quotations after visible answer text remain in the answer. Ambiguous opening quotes can still be folded. Plain Markdown documents are not classified as reasoning. Kelivo's unframed text tool results still require heuristic boundaries.

**Upgrading to 1.5.0:** existing records remain readable and their metadata is migrated without rewriting their bodies. New imports retain the source and use parser version 2. Use the reader menu to view/export the source or parse again; old records without a source ask you to select the original file. Reparsing retains previous stored revisions and keeps title/pin; reading positions and bookmarks may reset if message boundaries change. Backups contain conversations, originals, images and reading metadata, not appearance settings. Restore validates the full archive and merges with new IDs on conflicts. Current backup limits are 64 MiB per entry and 256 MiB total; damaged records are reported and retained rather than silently exported or deleted.

**1.5.1 rendering patch:** create populated text views only after Markdown parsing completes, avoiding empty-view premeasurement and stale text geometry. Font or line-spacing changes create a fresh layout. No storage format change or reimport is needed. The old failure was reproduced on hardware with layout diagnostics. The user reports no recurrence after installing 1.5.1, including minimum/maximum font and spacing; automated device tests remain unexecuted.

**1.6.0 reading and recovery:** enable quiet reading in Settings (off by default), use compact menus/long press for actions, and open tables fullscreen. The reader menu previews previous parsing history before restoration; unverifiable older history is rejected safely. Storage cleanup keeps current, previous, additional recovery checkpoints, all images, and unknown files. Only validated older revisions are eligible, and any change after preview requires refreshing confirmation. No transcript append/merge feature was added.

**1.7.0 organization:** choose folders above the library; use Manage folders to create, rename or delete. Deleting a folder moves its conversations to Unclassified without removing content. Long-press or multi-select to move. Name ordering uses the current display title and natural numbers (1, 2, 10); size ordering uses original UTF-8 Markdown bytes including embedded images. Unknown sizes remain last in either direction, and parsing-history disk usage is excluded. New imports start unclassified. Backup format 2 includes empty folders and assignments, remapping collisions into separate renamed folders; format 1 remains importable. Restore new backups with 1.7.0 or later.

**1.8.0 visual polish:** refined warm ivory/terracotta colors, shared typography and shapes, grouped settings and a reading sample. Settings moves to the library header; import has a text label. Cards retain counts, full import timestamps and original sizes with wrapping metadata. Selection animates tint/outline, sorting and pins animate placement, search crossfades, fold arrows rotate, and entering pages fade with a small translation. Compose follows the system animation scale. Long text and fold contents never animate their dimensions, preserving the 1.5.1 native text measurement lifecycle. No storage-format change. All 181 offline tests pass; device visuals and motion remain unverified.

**1.9.0 motion integration:** builds on 1.8.0 with fade/short-translation/scale feedback for batch controls, selection markers and import; checked/bookmark icon crossfades; pressed reader icons; shared opacity reveals for explicitly opened folds, without replay on scroll or bulk expansion. Image and table fullscreen windows animate in/out and release after exit; decoded viewer images fade in. No per-scroll entrances or text-dimension animation. Gestures and page-save lifecycle remain unchanged. All 181 offline tests pass; Release and AndroidTest compilation pass. The user completed visual/motion acceptance and reported no issues so far; automated device rendering and targeted zero-animation checks remain unexecuted.

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

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Normal tests use self-contained, synthetic examples. The five-file private regression corpus is optional and remains outside the repository. To run it locally, pass `-PchatscrollCorpusDir="/path/to/corpus"`; the expected files are `CLAUDE（1）.md` and `claude（2）.md` through `claude（5）.md`. Without the property the corpus test explicitly skips; when configured, missing files fail. Checks cover message roles, exact tool extents, answer placement, image references, and overlapping history. Corpus failure messages do not print conversation text.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · [Markwon](https://github.com/noties/Markwon) for Markdown rendering · [JLatexMath](https://github.com/opencollab/jlatexmath) for LaTeX · kotlinx-serialization for local storage

## License

[MIT](./LICENSE) © 2026 ChatScroll Contributors
