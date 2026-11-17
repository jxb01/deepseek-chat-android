/**
 * 原生批量导入的写入口：把一批已整理好的会话直接并入 IndexedDB。
 * 与设置里的"导入历史对话"不同，这里是合并（put）而不是覆盖，
 * 所以可以分批导入任意大的导出包。
 */
type MergeSession = {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: unknown[]
}

const openDb = () =>
  new Promise<IDBDatabase>((resolve, reject) => {
    const req = indexedDB.open('chat-demo', 11)
    req.onupgradeneeded = () => {
      const db = req.result
      const tx = req.transaction
      for (const name of ['sessions', 'files', 'sessionsStaging', 'filesStaging']) {
        if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, { keyPath: 'id' })
      }
      const summaries = db.objectStoreNames.contains('sessionSummaries')
        ? tx!.objectStore('sessionSummaries')
        : db.createObjectStore('sessionSummaries', { keyPath: 'id' })
      if (!summaries.indexNames.contains('fileIds')) {
        summaries.createIndex('fileIds', 'fileIds', { multiEntry: true })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })

const writeBatch = async (sessions: MergeSession[]) => {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(['sessions', 'sessionSummaries'], 'readwrite')
    const store = tx.objectStore('sessions')
    const summaries = tx.objectStore('sessionSummaries')
    for (const session of sessions) {
      store.put(session)
      summaries.put({
        id: session.id,
        title: session.title,
        createdAt: session.createdAt,
        updatedAt: session.updatedAt,
        fileIds: [],
      })
    }
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
  db.close()
}

type NativeBridge = { onBatchDone(id: string, ok: boolean): void }

export const installMergeBridge = () => {
  const native = () => (window as unknown as { DSHNative?: NativeBridge }).DSHNative
  ;(window as unknown as Record<string, unknown>).__dshMergeSessions = (id: string, json: string) => {
    writeBatch(JSON.parse(json) as MergeSession[])
      .then(() => native()?.onBatchDone(id, true))
      .catch(() => native()?.onBatchDone(id, false))
    return true
  }
  ;(window as unknown as Record<string, unknown>).__dshImportDone = () => {
    location.reload()
  }
}
