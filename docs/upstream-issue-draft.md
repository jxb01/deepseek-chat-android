### Scenario: chat-demo compiled into an Android APK

We build `chat-demo` with `pnpm build`, copy `dist/` into an Android app's assets and serve it through `WebViewAssetLoader` (`https://appassets.androidplatform.net/assets/www/index.html`), then import a real DeepSeek data export from **Settings → Data → Import chat history → Upload**.

### Environment

- Android 14 emulator (Pixel), system WebView
- chat-demo built from `main` (rspack, production)
- Export produced by DeepSeek (manifest `export_format_version: 3`)

### Steps

1. Build `dist`, wrap it in a WebView APK.
2. Open Settings → Data → Import chat history.
3. Upload a DeepSeek export ZIP.
4. Confirm the overwrite prompt.

### Actual result

| Input | Result |
|---|---|
| 2 conversations / 2.4 MB | imports in seconds, works |
| 1,078 conversations / 18.5 MB | modal buttons stay disabled for 3+ minutes, caches "finish", **no conversations are added** |
| 4,499 conversations / 637 MB (5,502 entries) | same, appears to finish, no data |

There is no progress indicator and the only failure signal is a transient line of red text inside the modal, so it looks like the import silently did nothing.

### What I found in the code

1. **Replace-only import.** `backup.ts` → `importHistory()` uses `replaceHistory(...)`: sessions are written to `sessionsStaging` and then the whole `sessions` store is replaced. A large archive cannot be split into several imports, because each import would wipe the previous one.
2. **Strict session schema.** `history/schema.ts` validates every message (`status` enum, RFC3339 time, non-negative integer counters, ...). A single unacceptable session aborts the entire import with `backupIncomplete`. Real exports contain `FILE`, `TOOL_SEARCH`, `READ_LINK`, `CONTENT_RISK` fragments that can fail this validation.
3. **Expensive ZIP path on Android.** `history/zipReader.ts` uses `file.slice(...).arrayBuffer()` for the central directory, each local header, each name and each extra field, and computes CRC32 byte-by-byte in JS. On Android the picked file is a MediaProvider `content://` URI, so each slice may reopen the file — 5,502 entries means tens of thousands of opens, and 741 MB uncompressed means ~740M JS loop iterations.

So it is not a hard size limit: it is a replace-only design plus an I/O/validation path that does not survive a real export on a phone.

### Workaround I implemented in the Android shell

Instead of going through the web import, the shell does:

1. Copy the picked ZIP to cache and parse it natively with `java.util.zip.ZipFile` (one pass, no SAF re-reads).
2. Normalise each session into the app's local model: linear messages, text only, millisecond timestamps, `status: "done"` — so schema validation cannot fail.
3. Write in batches of 50 sessions through a small global, `window.__dshMergeSessions(id, json)`, which merges into IndexedDB (`put`, not replace) and calls back into the shell when the batch is committed.
4. Show progress and reload the page when finished.

Result: the full 637 MB export (4,499 conversations) imports in **about 45 seconds** on the emulator. Attachments, branches, thinking blocks and citations are dropped in this path.

### Suggestion for the project

If a native/streamed import is interesting, the smallest upstream change would be:

- expose the local-db write path so a host can append sessions (`mergeSessions(sessions)`) instead of replacing, and
- let the importer consume an abstract byte source (Blob *or* host-provided stream) so wrappers can feed a `content://` file without `File.slice()` per entry.

Happy to send a PR for either piece. The wrapper and patch are open source here: https://github.com/jxb01/deepseek-chat-android