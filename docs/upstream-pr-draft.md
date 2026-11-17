Docs-only change: adds `docs/android-large-import.md` and a short pointer in the README.

### Why

When the static build is wrapped in an Android WebView app (assets + `WebViewAssetLoader`), importing a real DeepSeek export through **Settings → Data → Import chat history** does not work for large files:

| Input | Result |
|---|---|
| 2 conversations / 2.4 MB | imports in seconds |
| 1,078 conversations / 18.5 MB | buttons stay disabled 3+ min, no sessions added |
| 4,499 conversations / 637 MB (5,502 entries) | same |

There is no progress indicator and the only error is a transient red line in the modal, so it looks like nothing happened.

### Findings documented

1. `replaceHistory()` makes the import replace-only, so a large archive cannot be split into several imports.
2. `schema.ts` aborts the whole import if a single session fails validation; real exports contain `FILE` / `TOOL_SEARCH` / `READ_LINK` / `CONTENT_RISK` fragments that may not pass.
3. `zipReader.ts` does `file.slice()` per entry and CRC32 per byte in JS; on Android the file is a `content://` URI, so each slice can reopen the file (tens of thousands of opens, ~740M JS iterations for 741 MB).

### Wrapper-side workaround (reference implementation)

Parse the ZIP natively, normalise sessions to the local model, and merge in batches through a small global that performs `put()` instead of `replaceHistory()`. The full 637 MB export then imports in ~45 s. Code: https://github.com/jxb01/deepseek-chat-android (MIT).

If maintainers would like a lossless path upstream, the smallest changes are an append/merge entry point next to `replaceHistory()` and an abstract byte source for the importer (Blob or host stream) instead of `File.slice()` per entry.