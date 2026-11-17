# Web patch: merge bridge for batched imports

`chat-demo`'s settings importer replaces the entire local database, so a large
export cannot be split into several imports. This patch adds a single global that
merges sessions into IndexedDB instead:

```
window.__dshMergeSessions(batchId, json)   // json = Session[] in the app's local model
window.__dshImportDone()                   // reload after the last batch
```

Integration:

1. Copy `mergeBridge.ts` to `src/services/chat/mergeBridge.ts`.
2. In `src/main.tsx`:

```ts
import { installMergeBridge } from './services/chat/mergeBridge.js'
installMergeBridge()
```

3. Rebuild with `NODE_ENV=production pnpm exec rspack build`.

The bridge is optional for the web build; the Android shell calls it after parsing
the ZIP natively. If the bridge is missing, the native importer reports a batch
failure instead of corrupting data.
