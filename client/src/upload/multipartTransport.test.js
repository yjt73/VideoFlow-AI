import { describe, expect, it, vi } from 'vitest'
import { MultipartTransport } from './multipartTransport'

describe('MultipartTransport', () => {
  it('获取签名后直接 PUT Blob 并返回去引号的 ETag', async () => {
    const apiFetch = vi.fn(async () => ({
      partNumber: 2,
      url: 'http://minio.local/media?signature=secret'
    }))
    const directFetch = vi.fn(async () => ({
      ok: true,
      headers: new Headers()
    }))
    const transport = new MultipartTransport({ apiFetch, directFetch })
    const blob = new Blob(['part'])
    const controller = new AbortController()

    await expect(transport.uploadPart('session-1', 2, blob, controller.signal))
      .resolves.toBeUndefined()

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1/parts/2/url',
      { method: 'POST', signal: controller.signal }
    )
    expect(directFetch).toHaveBeenCalledWith(
      'http://minio.local/media?signature=secret',
      { method: 'PUT', body: blob, signal: controller.signal }
    )
  })

  it('按分片号排序完成请求并支持取消及访问地址', async () => {
    const apiFetch = vi.fn(async (path) => {
      if (path.endsWith('/complete')) return { id: 9 }
      if (path.endsWith('/access-url')) return { url: 'http://signed-get' }
      return null
    })
    const transport = new MultipartTransport({ apiFetch, directFetch: vi.fn() })

    await transport.complete('session-1')
    await transport.abort('session-1')
    await expect(transport.accessUrl(9)).resolves.toBe('http://signed-get')

    expect(apiFetch.mock.calls[0][1]).toEqual({ method: 'POST' })
    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1',
      { method: 'DELETE' }
    )
  })

  it('批量上报服务端已确认的分片进度', async () => {
    const apiFetch = vi.fn(async () => null)
    const transport = new MultipartTransport({ apiFetch, directFetch: vi.fn() })
    const controller = new AbortController()

    await transport.reportProgress('session-1', [1, 2], controller.signal)

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1/progress',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          partNumbers: [1, 2]
        }),
        signal: controller.signal
      }
    )
  })

  it('批量申请分片预签名地址', async () => {
    const apiFetch = vi.fn(async () => [
      { partNumber: 1, url: 'http://minio.local/part-1' },
      { partNumber: 2, url: 'http://minio.local/part-2' }
    ])
    const transport = new MultipartTransport({ apiFetch, directFetch: vi.fn() })
    const controller = new AbortController()

    await transport.presignParts('session-1', [1, 2], controller.signal)

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1/parts/urls',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ partNumbers: [1, 2] }),
        signal: controller.signal
      }
    )
  })

  it('MinIO 请求未发出时保留对象存储地址和原始原因', async () => {
    const apiFetch = vi.fn(async () => ({
      url: 'http://localhost:9000/media?signature=secret'
    }))
    const directFetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    const transport = new MultipartTransport({ apiFetch, directFetch })

    await expect(transport.uploadPart('session-1', 1, new Blob(['part'])))
      .rejects.toThrow('http://localhost:9000')
  })

  it('默认 fetch 保留浏览器全局对象上下文', async () => {
    const nativeFetch = globalThis.fetch
    vi.stubGlobal('fetch', function () {
      if (this !== globalThis) {
        throw new TypeError("Failed to execute 'fetch' on 'Window': Illegal invocation")
      }
      return Promise.resolve({
        ok: true,
        headers: new Headers()
      })
    })
    const transport = new MultipartTransport({
      apiFetch: vi.fn(async () => ({
        url: 'http://localhost:9000/media?signature=secret'
      }))
    })

    await expect(transport.uploadPart('session-1', 1, new Blob(['part'])))
      .resolves.toBeUndefined()

    vi.stubGlobal('fetch', nativeFetch)
  })
})
