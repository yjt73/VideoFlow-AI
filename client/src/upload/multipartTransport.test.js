import { describe, expect, it, vi } from 'vitest'
import { MultipartTransport } from './multipartTransport'

describe('MultipartTransport', () => {
  it('通过后端获取签名后直接 PUT Blob，并返回去引号的 ETag', async () => {
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
    expect(directFetch.mock.calls[0][1].headers).toBeUndefined()
    expect(etag).toBe('etag-2')
  })

  it('按分片号排序完成请求并支持取消及短期访问地址', async () => {
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

    expect(JSON.parse(apiFetch.mock.calls[0][1].body).parts)
      .toEqual([
        { partNumber: 1, etag: 'a' },
        { partNumber: 2, etag: 'b' },
        { partNumber: 3, etag: 'c' }
      ])
    expect(apiFetch).toHaveBeenCalledWith(
      '/media/multipart/session-1',
      { method: 'DELETE' }
    )
  })
})
