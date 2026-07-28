import { describe, expect, it, vi } from 'vitest'
import { UploadManager, UPLOAD_STATES } from './uploadManager'

const FILE_MD5 = '5d41402abc4b2a76b9719d911017c592'

const fileOf = (content = 'abcdef') => Object.assign(new Blob([content]), {
  name: 'demo.mp4',
  lastModified: 1
})

const memoryStorage = () => {
  const values = new Map()
  return {
    getItem: vi.fn((key) => values.get(key) ?? null),
    setItem: vi.fn((key, value) => values.set(key, value)),
    removeItem: vi.fn((key) => values.delete(key))
  }
}

const completedMedia = { id: 9, filename: 'demo.mp4', contentHash: FILE_MD5 }

const eventTarget = () => {
  const listeners = new Map()
  return {
    addEventListener: (type, listener) => listeners.set(type, listener),
    removeEventListener: (type) => listeners.delete(type),
    emit: (type) => listeners.get(type)?.()
  }
}

const createManager = (overrides = {}) => {
  const apiFetch = overrides.apiFetch ?? vi.fn(async (path) => {
    if (path === '/media/check-upload') {
      return {
        state: 'NEW',
        uploadId: 'upload-1',
        uploadedChunks: [],
        deduplicated: false,
        media: null
      }
    }
    if (path.startsWith('/media/complete-upload')) return completedMedia
    return 'ok'
  })
  const states = []
  const manager = new UploadManager({
    apiFetch,
    calculateFileMd5: overrides.calculateFileMd5 ?? (async (file, progress) => {
      progress(100)
      return FILE_MD5
    }),
    calculateChunkMd5: overrides.calculateChunkMd5 ?? (async () => FILE_MD5),
    storage: overrides.storage ?? memoryStorage(),
    concurrency: overrides.concurrency ?? 3,
    chunkSize: overrides.chunkSize ?? 1,
    sleep: overrides.sleep ?? (async () => {}),
    onlineTarget: overrides.onlineTarget ?? null,
    onChange: (snapshot) => states.push(snapshot.state)
  })
  return { manager, apiFetch, states }
}

describe('UploadManager', () => {
  it('完成 hashing、uploading、merging、completed 状态转换', async () => {
    const { manager, states } = createManager()

    const result = await manager.start(fileOf('abc'), 1)

    expect(result).toEqual(completedMedia)
    expect(states).toEqual(expect.arrayContaining([
      UPLOAD_STATES.HASHING,
      UPLOAD_STATES.UPLOADING,
      UPLOAD_STATES.MERGING,
      UPLOAD_STATES.COMPLETED
    ]))
    expect(manager.snapshot.progress).toBe(100)
  })

  it('同一时间最多上传三个分片', async () => {
    let active = 0
    let maximum = 0
    const releases = []
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        return { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') {
        active += 1
        maximum = Math.max(maximum, active)
        await new Promise((resolve) => releases.push(resolve))
        active -= 1
        return 'ok'
      }
      return completedMedia
    })
    const { manager } = createManager({ apiFetch })

    const upload = manager.start(fileOf('abcdef'), 1)
    await vi.waitFor(() => expect(releases).toHaveLength(3))
    while (releases.length) releases.shift()()
    await vi.waitFor(() => expect(apiFetch.mock.calls
      .filter(([path]) => path === '/media/upload-chunk').length).toBeGreaterThan(3))
    while (releases.length) releases.shift()()
    await upload

    expect(maximum).toBe(3)
  })

  it('只重试失败分片并保留已成功分片', async () => {
    const attempts = new Map()
    const apiFetch = vi.fn(async (path, options) => {
      if (path === '/media/check-upload') {
        return { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') {
        const index = Number(options.body.get('chunkIndex'))
        attempts.set(index, (attempts.get(index) ?? 0) + 1)
        if (index === 1 && attempts.get(index) === 1) {
          const error = new Error('temporary')
          error.status = 503
          throw error
        }
        return 'ok'
      }
      return completedMedia
    })
    const { manager } = createManager({ apiFetch })

    await manager.start(fileOf('abc'), 1)

    expect(attempts.get(0)).toBe(1)
    expect(attempts.get(1)).toBe(2)
    expect(attempts.get(2)).toBe(1)
  })

  it('上传检查遇到 503 时自动重试', async () => {
    let checks = 0
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        checks += 1
        if (checks === 1) {
          const error = new Error('redis unavailable')
          error.status = 503
          throw error
        }
        return { state: 'COMPLETED', deduplicated: true, media: completedMedia }
      }
      throw new Error('unexpected')
    })
    const { manager } = createManager({ apiFetch })

    await manager.start(fileOf('abc'), 1)

    expect(checks).toBe(2)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('使用固定退避并在第八次失败后暂停', async () => {
    const delays = []
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        return { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') {
        const error = new Error('temporary')
        error.status = 503
        throw error
      }
      return completedMedia
    })
    const { manager } = createManager({
      apiFetch,
      sleep: async (delay) => delays.push(delay)
    })

    await manager.start(fileOf('a'), 1)

    expect(delays).toEqual([1000, 2000, 4000, 8000, 15000, 30000, 30000])
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)
    expect(manager.snapshot.retryCount).toBe(8)
  })

  it('并发分片累计八次连续失败后全局暂停', async () => {
    let uploadAttempts = 0
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        return { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') {
        uploadAttempts += 1
        const error = new Error('temporary')
        error.status = 503
        throw error
      }
      return completedMedia
    })
    const { manager } = createManager({ apiFetch, concurrency: 3 })

    await manager.start(fileOf('abc'), 1)

    expect(uploadAttempts).toBeGreaterThanOrEqual(8)
    expect(uploadAttempts).toBeLessThanOrEqual(10)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)
    expect(manager.snapshot.retryCount).toBe(8)
  })

  it('取消旧文件哈希后不会覆盖新文件的哈希', async () => {
    let resolveOldHash
    const calculateFileMd5 = vi.fn((file) => {
      if (file.name === 'old.mp4') {
        return new Promise((resolve) => {
          resolveOldHash = resolve
        })
      }
      return Promise.resolve(FILE_MD5)
    })
    const { manager } = createManager({ calculateFileMd5 })
    const oldFile = Object.assign(new Blob(['old']), { name: 'old.mp4' })
    const newFile = Object.assign(new Blob(['new']), { name: 'new.mp4' })

    const oldStart = manager.start(oldFile, 1)
    await manager.cancel()
    const newStart = manager.start(newFile, 1)
    resolveOldHash('149603e6c03516362a8da23f624db945')
    await Promise.all([oldStart, newStart])

    expect(manager.fileMd5).toBe(FILE_MD5)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('哈希阶段暂停后可重新计算并继续上传', async () => {
    let firstAttempt = true
    let rejectFirst
    const calculateFileMd5 = vi.fn(() => {
      if (firstAttempt) {
        firstAttempt = false
        return new Promise((resolve, reject) => {
          rejectFirst = reject
        })
      }
      return Promise.resolve(FILE_MD5)
    })
    const { manager } = createManager({ calculateFileMd5 })

    const start = manager.start(fileOf('a'), 1)
    manager.pause()
    rejectFirst(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    await start
    await manager.resume()

    expect(calculateFileMd5).toHaveBeenCalledTimes(2)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('继续上传前重新查询服务端分片状态', async () => {
    let checks = 0
    let releaseSleep
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        checks += 1
        return {
          state: checks === 1 ? 'NEW' : 'UPLOADING',
          uploadId: 'upload-1',
          uploadedChunks: checks === 1 ? [] : [0]
        }
      }
      if (path === '/media/upload-chunk') {
        const error = new Error('temporary')
        error.status = 503
        throw error
      }
      return completedMedia
    })
    const { manager } = createManager({
      apiFetch,
      sleep: () => new Promise((resolve) => {
        releaseSleep = resolve
      })
    })
    const start = manager.start(fileOf('ab'), 1)
    await vi.waitFor(() => expect(manager.snapshot.state).toBe(UPLOAD_STATES.RETRYING))
    manager.pause()
    releaseSleep()
    await start

    apiFetch.mockImplementation(async (path) => {
      if (path === '/media/check-upload') {
        checks += 1
        return { state: 'UPLOADING', uploadId: 'upload-1', uploadedChunks: [0, 1] }
      }
      return completedMedia
    })
    await manager.resume()

    expect(checks).toBeGreaterThanOrEqual(2)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('达到上限暂停后在网络恢复时自动继续', async () => {
    const onlineTarget = eventTarget()
    let recovered = false
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        return recovered
          ? { state: 'UPLOADING', uploadId: 'upload-1', uploadedChunks: [0] }
          : { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') {
        const error = new Error('offline')
        error.status = 503
        throw error
      }
      return completedMedia
    })
    const { manager } = createManager({ apiFetch, onlineTarget })
    await manager.start(fileOf('a'), 1)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)

    recovered = true
    onlineTarget.emit('online')
    await vi.waitFor(() => expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED))
  })

  it('合并仅执行一次', async () => {
    const { manager, apiFetch } = createManager()

    await manager.start(fileOf('abc'), 1)

    expect(apiFetch.mock.calls
      .filter(([path]) => path.startsWith('/media/complete-upload'))).toHaveLength(1)
  })

  it('合并达到重试上限后继续时不重新上传分片', async () => {
    let completeFailures = 0
    let resumed = false
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        return resumed
          ? { state: 'UPLOADING', uploadId: 'upload-1', uploadedChunks: [0] }
          : { state: 'NEW', uploadId: 'upload-1', uploadedChunks: [] }
      }
      if (path === '/media/upload-chunk') return 'ok'
      if (path.startsWith('/media/complete-upload')) {
        if (completeFailures < 8) {
          completeFailures += 1
          const error = new Error('minio unavailable')
          error.status = 503
          throw error
        }
        return completedMedia
      }
      throw new Error('unexpected')
    })
    const { manager } = createManager({ apiFetch })

    await manager.start(fileOf('a'), 1)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.PAUSED)
    resumed = true
    await manager.resume()

    expect(apiFetch.mock.calls
      .filter(([path]) => path === '/media/upload-chunk')).toHaveLength(1)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.COMPLETED)
  })

  it('明确收到 uploadId 过期响应时才清除本地记录', async () => {
    const storage = memoryStorage()
    storage.setItem(`upload:1:${FILE_MD5}`, 'expired-upload')
    let checks = 0
    const apiFetch = vi.fn(async (path) => {
      if (path === '/media/check-upload') {
        checks += 1
        if (checks === 1) {
          const error = new Error('expired')
          error.status = 404
          error.errorCode = 'UPLOAD_SESSION_EXPIRED'
          throw error
        }
        return { state: 'COMPLETED', deduplicated: true, media: completedMedia }
      }
      throw new Error('unexpected')
    })
    const { manager } = createManager({ apiFetch, storage })

    await manager.start(fileOf('abc'), 1)

    expect(storage.removeItem).toHaveBeenCalledWith(`upload:1:${FILE_MD5}`)
    expect(checks).toBe(2)
  })

  it('普通 404 不会清除可续传 uploadId', async () => {
    const storage = memoryStorage()
    storage.setItem(`upload:1:${FILE_MD5}`, 'upload-1')
    const error = new Error('route not found')
    error.status = 404
    const apiFetch = vi.fn(async () => {
      throw error
    })
    const { manager } = createManager({ apiFetch, storage })

    await manager.start(fileOf('abc'), 1)

    expect(storage.removeItem).not.toHaveBeenCalled()
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.FAILED)
  })

  it('秒传命中时不上传分片', async () => {
    const apiFetch = vi.fn(async () => ({
      state: 'COMPLETED',
      deduplicated: true,
      media: completedMedia
    }))
    const { manager } = createManager({ apiFetch })

    await manager.start(fileOf('abc'), 1)

    expect(apiFetch).toHaveBeenCalledTimes(1)
    expect(manager.snapshot.deduplicated).toBe(true)
  })

  it('取消时调用服务端清理并删除本地会话', async () => {
    const storage = memoryStorage()
    const { manager, apiFetch } = createManager({ storage })
    manager.file = fileOf('a')
    manager.userId = 1
    manager.fileMd5 = FILE_MD5
    manager.uploadId = 'upload-1'
    storage.setItem(`upload:1:${FILE_MD5}`, 'upload-1')

    await manager.cancel()

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/upload?uploadId=upload-1',
      expect.objectContaining({ method: 'DELETE' })
    )
    expect(storage.removeItem).toHaveBeenCalledWith(`upload:1:${FILE_MD5}`)
    expect(manager.snapshot.state).toBe(UPLOAD_STATES.CANCELLED)
  })
})
