export class MultipartTransport {
  constructor({ apiFetch, directFetch = (...args) => globalThis.fetch(...args) }) {
    this.apiFetch = apiFetch
    this.directFetch = directFetch
  }

  check(metadata, signal) {
    return this.apiFetch('/media/multipart/check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(metadata),
      signal
    })
  }

  async uploadPart(sessionId, partNumber, blob, signal) {
    const signed = await this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}/parts/${partNumber}/url`,
      { method: 'POST', signal }
    )
    let response
    try {
      response = await this.directFetch(signed.url, {
        method: 'PUT',
        body: blob,
        signal
      })
    } catch (cause) {
      const error = new Error(
        `MinIO 分片直传请求未发出（${new URL(signed.url).origin}）：${cause.message}`
      )
      error.cause = cause
      throw error
    }
    if (!response.ok) {
      const error = new Error(`分片直传失败 (${response.status})`)
      error.status = response.status
      throw error
    }
    const etag = response.headers.get('etag')?.replaceAll('"', '')
    if (!etag) {
      throw new Error('对象存储未返回 ETag')
    }
    return etag
  }

  complete(sessionId, parts, signal) {
    return this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}/complete`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          parts: [...parts].sort((left, right) => left.partNumber - right.partNumber)
        }),
        ...(signal ? { signal } : {})
      }
    )
  }

  abort(sessionId) {
    return this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}`,
      { method: 'DELETE' }
    )
  }

  async accessUrl(mediaId) {
    const access = await this.apiFetch(`/media/${mediaId}/access-url`)
    return access.url
  }
}
