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

  presignParts(sessionId, partNumbers, signal) {
    return this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}/parts/urls`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ partNumbers }),
        signal
      }
    )
  }

  async uploadPart(sessionId, partNumber, blob, signal, signedUrl = null) {
    const signed = signedUrl
      ? { url: signedUrl }
      : await this.apiFetch(
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
  }

  complete(sessionId, signal) {
    return this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}/complete`,
      {
        method: 'POST',
        ...(signal ? { signal } : {})
      }
    )
  }

  reportProgress(sessionId, partNumbers, signal) {
    return this.apiFetch(
      `/media/multipart/${encodeURIComponent(sessionId)}/progress`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ partNumbers }),
        signal
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
