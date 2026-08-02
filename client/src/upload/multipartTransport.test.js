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
      headers: new Headers({ ETag: '"etag-2"' })
    }))
    const transport = new MultipartTransport({ apiFetch, directFetch })
    const blob = new Blob(['part'])
    const controller = new AbortController()

    const etag = await transport.uploadPart('session-1', 2, blob, controller.signal)

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1/parts/2/url',
      { method: 'POST', signal: controller.signal }
    )
    expect(directFetch).toHaveBeenCalledWith(
      'http://minio.local/media?signature=secret',
      { method: 'PUT', body: blob, signal: controller.signal }
    )
    expect(etag).toBe('etag-2')
  })

  it('按分片号排序完成请求并支持取消及访问地址', async () => {
    const apiFetch = vi.fn(async (path) => {
      if (path.endsWith('/complete')) return { id: 9 }
      if (path.endsWith('/access-url')) return { url: 'http://signed-get' }
      return null
    })
    const transport = new MultipartTransport({ apiFetch, directFetch: vi.fn() })

    await transport.complete('session-1', [
      { partNumber: 3, etag: 'c' },
      { partNumber: 1, etag: 'a' },
      { partNumber: 2, etag: 'b' }
    ])
    await transport.abort('session-1')
    await expect(transport.accessUrl(9)).resolves.toBe('http://signed-get')

    expect(JSON.parse(apiFetch.mock.calls[0][1].body).parts).toEqual([
      { partNumber: 1, etag: 'a' },
      { partNumber: 2, etag: 'b' },
      { partNumber: 3, etag: 'c' }
    ])
    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1',
      { method: 'DELETE' }
    )
  })

  it('批量上报服务端已确认的分片进度', async () => {
    const apiFetch = vi.fn(async () => null)
    const transport = new MultipartTransport({ apiFetch, directFetch: vi.fn() })
    const controller = new AbortController()

    await transport.reportProgress('session-1', [
      { partNumber: 1, etag: 'etag-1' },
      { partNumber: 2, etag: 'etag-2' }
    ], controller.signal)

    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1/progress',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          parts: [
            { partNumber: 1, etag: 'etag-1' },
            { partNumber: 2, etag: 'etag-2' }
          ]
        }),
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
        headers: new Headers({ ETag: '"etag-1"' })
      })
    })
    const transport = new MultipartTransport({
      apiFetch: vi.fn(async () => ({
        url: 'http://localhost:9000/media?signature=secret'
      }))
    })

    await expect(transport.uploadPart('session-1', 1, new Blob(['part'])))
      .resolves.toBe('etag-1')

    vi.stubGlobal('fetch', nativeFetch)
  })
})
