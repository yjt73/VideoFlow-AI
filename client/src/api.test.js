import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './api'

const jsonResponse = (body, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: new Headers({ 'content-type': 'application/json' }),
  json: async () => body
})

describe('apiFetch 请求控制', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
      removeItem: vi.fn()
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('外部取消信号会终止 fetch', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'))
      })
    })))
    const controller = new AbortController()

    const request = apiFetch('/media/list', { signal: controller.signal })
    controller.abort()

    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('请求超时后抛出可重试错误', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((url, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => {
        reject(new DOMException('aborted', 'AbortError'))
      })
    })))

    const request = apiFetch('/media/list', { timeoutMs: 100 })
    const assertion = expect(request).rejects.toMatchObject({
      code: 'REQUEST_TIMEOUT',
      retryable: true
    })
    await vi.advanceTimersByTimeAsync(100)

    await assertion
  })

  it('保留后端状态和统一错误信息', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({
      code: 503,
      message: 'Redis 暂时不可用',
      requestId: 'request-1'
    }, 503)))

    await expect(apiFetch('/media/check-upload')).rejects.toMatchObject({
      status: 503,
      code: 503,
      message: 'Redis 暂时不可用',
      requestId: 'request-1'
    })
  })
})
