# DeepSeek Chat for Android (WebView shell + native export import)

An Android wrapper around [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo),
plus a **native batch importer** for large DeepSeek data exports.

## Why this exists

`chat-demo` is a static site. Wrapping its `dist/` in a WebView APK works fine —
until you import a real DeepSeek export.

On Android, the ZIP picked from the file picker is a `content://` URI served by
MediaProvider. The web importer reads it through `File.slice()` once per ZIP entry
(central directory + local header + name + extra field), which on SAF means reopening
the file thousands of times, and it computes CRC32 byte-by-byte in JavaScript.
A 637 MB export (5,502 entries, 741 MB uncompressed) either takes many minutes with no
progress indicator, or ends without importing anything.

Worse, the settings importer is **replace-only** (`replaceHistory`): it stages every
session and then swaps the whole database, so you cannot split a big export into
several imports — each one overwrites the previous.

## What this app does

- Serves the built `chat-demo` from app assets through `WebViewAssetLoader`
  (`https://appassets.androidplatform.net/assets/www/index.html`) so IndexedDB and
  storage work with a normal origin.
- Adds a **导入ZIP / Import ZIP** button that:
  1. copies the picked ZIP to cache,
  2. parses it natively with `java.util.zip.ZipFile` (one pass, no SAF reopens),
  3. normalises every session into the app's local message model
     (linear messages, text-only, timestamps in ms),
  4. writes it in batches of 50 sessions through `window.__dshMergeSessions(...)`,
     which **merges** into IndexedDB instead of replacing,
  5. shows progress and reloads the page when done.
- Measured on an Android 14 emulator: the full 637 MB export (4,499 conversations)
  imports in about 45 seconds.

## Requirements

- Android Studio (AGP 8.5+, JDK 17/21), Android SDK 34
- Node.js 22+ and pnpm to build the web bundle

## Build

```bash
# 1. build the web app
git clone https://github.com/deepseek-ai/chat-demo.git
cd chat-demo && pnpm install && NODE_ENV=production pnpm exec rspack build

# 2. copy dist into the shell
cp -r dist/* ../deepseek-chat-android/app/src/main/assets/www/

# 3. build the APK
cd ../deepseek-chat-android
./gradlew assembleDebug
```

Optional but recommended: apply `web-patch/mergeBridge.ts` to `chat-demo`
(see `web-patch/README.md`) so the bridge exists in the bundle.

## Limitations

- Attachments (images/files) are skipped; only text is imported.
- Message branches are flattened to the original message order.
- Thinking blocks, search citations and link previews are dropped.
- Titles and timestamps are preserved.

## License

MIT. `chat-demo` itself is MIT and belongs to DeepSeek.
