import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, askVideoQuestion } from './api'

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

    await expect(apiFetch('/media/multipart/check')).rejects.toMatchObject({
      status: 503,
      code: 503,
      message: 'Redis 暂时不可用',
      requestId: 'request-1'
    })
  })

  it('保留稳定业务错误码供上传会话过期判断', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({
      code: 404,
      errorCode: 'UPLOAD_SESSION_EXPIRED',
      message: '上传会话已过期'
    }, 404)))

    await expect(apiFetch('/media/multipart/check')).rejects.toMatchObject({
      status: 404,
      errorCode: 'UPLOAD_SESSION_EXPIRED'
    })
  })

  it('提交视频问答请求', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({
      code: 200,
      data: {
        conversationId: 'conversation-1',
        answer: '第三章主要讲评分规则。',
        citations: [{ timestampMs: 120000, source: 'ASR', content: '评分规则' }]
      }
    }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await askVideoQuestion(9, {
      conversationId: 'conversation-1',
      question: '第三章讲了什么？'
    })

    expect(fetchMock).toHaveBeenCalledWith(
      '/media/9/qa',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          conversationId: 'conversation-1',
          question: '第三章讲了什么？'
        })
      })
    )
    expect(result.answer).toContain('评分规则')
  })
})
