import { describe, expect, it, vi } from 'vitest'
import { UploadManager, UPLOAD_STATES } from './uploadManager'

const FILE_MD5 = '5d41402abc4b2a76b9719d911017c592'
const completedMedia = { id: 9, filename: 'demo.mp4', contentHash: FILE_MD5 }

const fileOf = (content = 'abcdef') => Object.assign(
  new Blob([content], { type: 'video/mp4' }),
  { name: 'demo.mp4', lastModified: 1 }
)

const memoryStorage = () => {
  const values = new Map()
  return {
    getItem: vi.fn((key) => values.get(key) ?? null),
    setItem: vi.fn((key, value) => values.set(key, value)),
    removeItem: vi.fn((key) => values.delete(key))
  }
}

const eventTarget = () => {
  const listeners = new Map()
  return {
    addEventListener: (type, listener) => listeners.set(type, listener),
    removeEventListener: (type) => listeners.delete(type),
    emit: (type) => listeners.get(type)?.()
  }
}

const createTransport = (overrides = {}) => ({
  check: overrides.check ?? vi.fn(async () => ({
    state: 'UPLOADING',
    sessionId: 'session-1',
    partSize: 1,
    totalParts: 3
  })),
  uploadPart: overrides.uploadPart ?? vi.fn(async (_sessionId, partNumber) => `etag-${partNumber}`),
  complete: overrides.complete ?? vi.fn(async () => completedMedia),
  abort: overrides.abort ?? vi.fn(async () => {}),
  accessUrl: overrides.accessUrl ?? vi.fn()
})

const createManager = (overrides = {}) => {
  const transport = overrides.transport ?? createTransport()
  const states = []
  const manager = new UploadManager({
    transport,
    calculateFileMd5: overrides.calculateFileMd5 ?? (async (_file, progress) => {
      progress(100)
      return FILE_MD5
    }),
    storage: overrides.storage ?? memoryStorage(),
    concurrency: overrides.concurrency ?? 3,
    chunkSize: overrides.chunkSize ?? 1,
    sleep: overrides.sleep ?? (async () => {}),
    onlineTarget: overrides.onlineTarget ?? null,
    onChange: (snapshot) => states.push(snapshot.state)
  })
  return { manager, transport, states }
}

describe('UploadManager', () => {
  it('完成哈希、三路分片直传和合并状态转换', async () => {
    const { manager, transport, states } = createManager()

    await expect(manager.start(fileOf('abc'), 1)).resolves.toEqual(completedMedia)

    expect(transport.check).toHaveBeenCalledWith({
      filename: 'demo.mp4',
      contentHash: FILE_MD5,
      sizeBytes: 3,
      contentType: 'video/mp4',
      totalParts: 3
    }, expect.any(AbortSignal))
    expect(transport.uploadPart).toHaveBeenCalledTimes(3)
    expect(transport.complete).toHaveBeenCalledWith('session-1', [
      { partNumber: 1, etag: 'etag-1' },
      { partNumber: 2, etag: 'etag-2' },
      { partNumber: 3, etag: 'etag-3' }
    ], expect.any(AbortSignal))
    expect(states).toEqual(expect.arrayContaining([
      UPLOAD_STATES.HASHING,
      UPLOAD_STATES.UPLOADING,
      UPLOAD_STATES.MERGING,
      UPLOAD_STATES.COMPLETED
    ]))
  })

  it('同一时间最多直传三个分片', async () => {
    let active = 0
    let maximum = 0
    const releases = []
    const transport = createTransport({
      check: vi.fn(async () => ({
        state: 'UPLOADING',
        sessionId: 'session-1',
        partSize: 1,
        totalParts: 6
      })),
      uploadPart: vi.fn(async (_sessionId, partNumber) => {
        active += 1
        maximum = Math.max(maximum, active)
        await new Promise((resolve) => releases.push(resolve))
        active -= 1
        return `etag-${partNumber}`
      })
    })
    const { manager } = createManager({ transport })

    const upload = manager.start(fileOf('abcdef'), 1)
    await vi.waitFor(() => expect(releases).toHaveLength(3))
    while (releases.length) releases.shift()()
    await vi.waitFor(() => expect(transport.uploadPart).toHaveBeenCalledTimes(6))
    while (releases.length) releases.shift()()
    await upload

    expect(maximum).toBe(3)
  })

  it('暂停中止当前 PUT，并保留已经完成的 ETag 供恢复使用', async () => {
    const storage = memoryStorage()
    let releaseSecond
    const transport = createTransport({
      check: vi.fn(async () => ({
        state: 'UPLOADING',
        sessionId: 'session-1',
        partSize: 1,
        totalParts: 2
      })),
      uploadPart: vi.fn(async (_sessionId, partNumber, _blob, signal) => {
        if (partNumber === 1) return 'etag-1'
        return new Promise((resolve, reject) => {
          releaseSecond = resolve
          signal.addEventListener('abort', () => reject(
            Object.assign(new Error('aborted'), { name: 'AbortError' })
          ))
        })
      })
    })
    const { manager } = createManager({ transport, storage, concurrency: 1 })

    const start = manager.start(fileOf('ab'), 1)
    await vi.waitFor(() => expect(transport.uploadPart).toHaveBeenCalledTimes(2))
    manager.pause()
    await start

    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)
    expect(manager.snapshot.completedParts).toEqual([{ partNumber: 1, etag: 'etag-1' }])
    expect(JSON.parse(storage.setItem.mock.calls.at(-1)[1]).completedParts)
      .toEqual([{ partNumber: 1, etag: 'etag-1' }])

    transport.uploadPart.mockImplementation(async (_sessionId, partNumber) => `etag-${partNumber}`)
    releaseSecond?.('etag-2')
    await manager.resume()
    expect(transport.uploadPart.mock.calls.filter(([, partNumber]) => partNumber === 1))
      .toHaveLength(1)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('连续失败八次后按固定指数序列暂停', async () => {
    const delays = []
    const error = Object.assign(new Error('temporary'), { status: 503 })
    const transport = createTransport({
      check: vi.fn(async () => ({
        state: 'UPLOADING',
        sessionId: 'session-1',
        partSize: 1,
        totalParts: 1
      })),
      uploadPart: vi.fn(async () => { throw error })
    })
    const { manager } = createManager({
      transport,
      sleep: async (delay) => delays.push(delay)
    })

    await manager.start(fileOf('a'), 1)

    expect(delays).toEqual([1000, 2000, 4000, 8000, 15000, 30000, 30000])
    expect(transport.uploadPart).toHaveBeenCalledTimes(8)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)
  })

  it('网络恢复后自动继续', async () => {
    const onlineTarget = eventTarget()
    let recovered = false
    const transport = createTransport({
      check: vi.fn(async () => ({
        state: 'UPLOADING',
        sessionId: 'session-1',
        partSize: 1,
        totalParts: 1
      })),
      uploadPart: vi.fn(async () => {
        if (!recovered) throw Object.assign(new Error('offline'), { status: 503 })
        return 'etag-1'
      })
    })
    const { manager } = createManager({ transport, onlineTarget })
    await manager.start(fileOf('a'), 1)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)

    recovered = true
    onlineTarget.emit('online')
    await vi.waitFor(() => expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED))
  })

  it('秒传命中时不上传任何分片', async () => {
    const transport = createTransport({
      check: vi.fn(async () => ({ state: 'COMPLETED', media: completedMedia }))
    })
    const { manager } = createManager({ transport })

    await manager.start(fileOf('abc'), 1)

    expect(transport.uploadPart).not.toHaveBeenCalled()
    expect(manager.snapshot.deduplicated).toBe(true)
  })

  it('取消上传会调用服务端 Abort 并清理本地会话', async () => {
    const storage = memoryStorage()
    const transport = createTransport()
    const { manager } = createManager({ transport, storage })
    manager.file = fileOf('a')
    manager.userId = 1
    manager.fileMd5 = FILE_MD5
    manager.sessionId = 'session-1'

    await manager.cancel()

    expect(transport.abort).toHaveBeenCalledWith('session-1')
    expect(storage.removeItem).toHaveBeenCalled()
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.CANCELLED)
  })
})
