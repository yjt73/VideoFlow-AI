import { describe, expect, it, vi } from 'vitest'
import { calculateChunkMd5, calculateFileMd5 } from './md5'

class FakeWorker {
  constructor() {
    this.postMessage = vi.fn()
    this.terminate = vi.fn()
    this.listeners = new Map()
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener)
  }

  removeEventListener(type) {
    this.listeners.delete(type)
  }

  emitMessage(data) {
    this.listeners.get('message')?.({ data })
  }

  emitError(message) {
    this.listeners.get('error')?.({ message })
  }
}

describe('MD5 哈希封装', () => {
  it('返回 Worker 计算的整文件 MD5', async () => {
    const worker = new FakeWorker()
    const file = new Blob(['hello'])

    const resultPromise = calculateFileMd5(file, undefined, () => worker)
    worker.emitMessage({ type: 'done', md5: '5d41402abc4b2a76b9719d911017c592' })

    await expect(resultPromise).resolves.toBe('5d41402abc4b2a76b9719d911017c592')
    expect(worker.postMessage).toHaveBeenCalledWith({ file })
    expect(worker.terminate).toHaveBeenCalledOnce()
  })

  it('转发 Worker 的哈希进度', async () => {
    const worker = new FakeWorker()
    const onProgress = vi.fn()
    const resultPromise = calculateFileMd5(new Blob(['hello']), onProgress, () => worker)

    worker.emitMessage({ type: 'progress', progress: 40 })
    worker.emitMessage({ type: 'done', md5: '5d41402abc4b2a76b9719d911017c592' })
    await resultPromise

    expect(onProgress).toHaveBeenCalledWith(40)
  })

  it('Worker 失败时拒绝 Promise 并终止 Worker', async () => {
    const worker = new FakeWorker()
    const resultPromise = calculateFileMd5(new Blob(['hello']), undefined, () => worker)

    worker.emitError('worker failed')

    await expect(resultPromise).rejects.toThrow('worker failed')
    expect(worker.terminate).toHaveBeenCalledOnce()
  })

  it('计算单分片 MD5', async () => {
    await expect(calculateChunkMd5(new Blob(['hello'])))
      .resolves.toBe('5d41402abc4b2a76b9719d911017c592')
  })
})
