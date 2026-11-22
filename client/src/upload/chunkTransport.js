const PART_SIZE = 5 * 1024 * 1024

export class ChunkUploadTransport {
  constructor({ apiFetch }) {
    this.apiFetch = apiFetch
    this.totalParts = new Map()
  }

  async check(metadata, signal) {
    let sessionId = metadata.sessionId
    let completedParts = []
    if (sessionId) {
      try {
        const uploaded = await this.apiFetch(`/media/upload-status?uploadId=${encodeURIComponent(sessionId)}`, { signal })
        completedParts = uploaded.map((index) => index + 1)
      } catch (error) {
        if (error.status !== 400 && error.status !== 404) throw error
        sessionId = null
      }
    }
    if (!sessionId) {
      const params = new URLSearchParams({ filename: metadata.filename, totalChunks: String(metadata.totalParts) })
      sessionId = await this.apiFetch(`/media/init-upload?${params}`, { method: 'POST', signal })
    }
    this.totalParts.set(sessionId, metadata.totalParts)
    return { state: 'UPLOADING', sessionId, partSize: PART_SIZE, totalParts: metadata.totalParts, completedParts }
  }

  async presignParts(_sessionId, partNumbers) {
    return partNumbers.map((partNumber) => ({ partNumber, url: 'server-upload' }))
  }

  async uploadPart(sessionId, partNumber, blob, signal) {
    const params = new URLSearchParams({
      uploadId: sessionId,
      chunkIndex: String(partNumber - 1),
      totalChunks: String(this.totalParts.get(sessionId))
    })
    const body = new FormData()
    body.append('file', blob, `part-${partNumber}`)
    await this.apiFetch(`/media/upload-chunk?${params}`, { method: 'POST', body, signal })
  }

  reportProgress() {
    // The active API records each part when upload-chunk succeeds.
  }

  complete(sessionId, signal) {
    return this.apiFetch(`/media/complete-upload?uploadId=${encodeURIComponent(sessionId)}`, { method: 'POST', signal })
  }

  abort(sessionId) {
    return this.apiFetch(`/media/upload?uploadId=${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
  }

  async accessUrl(mediaId) {
    const access = await this.apiFetch(`/media/${mediaId}/access-url`)
    return access.url
  }
}
