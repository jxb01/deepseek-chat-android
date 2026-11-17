# Large DeepSeek export import on Android: analysis and fix

## Symptom

Importing a real DeepSeek export into `chat-demo` inside an Android WebView APK:

| Input | Result |
|---|---|
| 2 conversations, 2.4 MB | imports in seconds |
| 1,078 conversations, 18.5 MB | dialog spins for minutes, no data |
| 4,499 conversations, 637 MB | same, then gives up |

## Root causes

1. **Replace-only import.** `importHistory()` stages all sessions and then replaces
   `sessions` in one transaction. Splitting a large archive into several imports is
   impossible — each import would wipe the previous one.
2. **Strict session schema.** Every message must match an exact shape; one bad
   session aborts the whole import (`backupIncomplete`). Exports contain
   `FILE`, `TOOL_SEARCH`, `READ_LINK`, `CONTENT_RISK` fragments that may not
   satisfy the validator.
3. **Expensive parsing path.** The ZIP reader performs `file.slice()` for the central
   directory, each local header, name and extra field, and computes CRC32 byte by byte
   in JS. On Android the file is a MediaProvider `content://` URI, so each slice can
   reopen the file — 5,502 entries means tens of thousands of opens, and 741 MB of
   content means ~740M JS loop iterations.
4. **No progress / transient errors.** The dialog has no progress and the only error
   is a line of red text inside the modal, which looks like "nothing happened".

## Fix (this repository)

Do the import outside the web file path:

1. Native parse with `java.util.zip.ZipFile` — one open, no SAF re-reads.
2. Normalise each session to the app's local model: linear messages, text only,
   millisecond timestamps, `status: "done"`.
3. Merge in batches of 50 through `window.__dshMergeSessions(...)`
   (IndexedDB `put`, not replace), so imports are resumable and never wipe data.
4. Report progress in the shell and reload when finished.

Result: 4,499 conversations / 637 MB in ~45 s on an Android 14 emulator.

## Trade-offs

Attachments, message branches, thinking blocks and citations are dropped.
If the maintainers want lossless import, the merge bridge can be extended to accept
a file-reader abstraction (native stream) instead of a `File`.

## Update: why the first native attempt crashed

The first version also carried image attachments: it read each `*_files/` entry,
base64-encoded it and pushed it through `evaluateJavascript`. On a 637 MB export that
allocated multi-megabyte strings inside a 192 MB app heap and the process died with
`OutOfMemoryError: Failed to allocate a 10175544 byte allocation` in
`Base64.encodeToString`. It also accumulated every parsed session in a list before
writing, which made the peak worse.

Current behaviour: text-only, streamed in batches of 50 sessions (no full-list
accumulation), and a hidden long-press trigger in the top strip. Full export:
4,499 conversations in under a minute on a clean app data directory.

## Update 2: conversations opened with only the first message

The first text-only version wrote `parentId` but not `selectedChildId`.
The local session model stores the current branch as a chain of `selectedChildId`
pointers from the root (see `history/toLocal.ts`), so without them the UI rendered
only the root message.

Fix: after building the linear message list, set `selectedChildId` of message *i* to
message *i+1* and leave the last one without it, plus `rootMessageId` = first id.
Verified on a 420-message conversation: the whole history now scrolls.
