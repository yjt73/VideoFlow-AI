import { describe, expect, it, vi } from 'vitest'
import { ChunkUploadTransport } from './chunkTransport'

describe('ChunkUploadTransport', () => {
  it('creates a session and sends a chunk to the active upload API', async () => {
    const apiFetch = vi.fn().mockResolvedValueOnce('upload-1').mockResolvedValueOnce(null).mockResolvedValueOnce({ id: 7 })
    const transport = new ChunkUploadTransport({ apiFetch })
    const signal = new AbortController().signal
    const session = await transport.check({ filename: 'test.mp4', totalParts: 2 }, signal)
    const parts = await transport.presignParts(session.sessionId, [1], signal)
    await transport.uploadPart(session.sessionId, 1, new Blob(['video']), signal, parts[0].url)
    await expect(transport.complete(session.sessionId, signal)).resolves.toEqual({ id: 7 })
    expect(session).toEqual({ state: 'UPLOADING', sessionId: 'upload-1', partSize: 5 * 1024 * 1024, totalParts: 2, completedParts: [] })
    expect(apiFetch.mock.calls[0][0]).toContain('/media/init-upload?')
    expect(apiFetch.mock.calls[1][0]).toContain('/media/upload-chunk?uploadId=upload-1&chunkIndex=0&totalChunks=2')
    expect(apiFetch.mock.calls[1][1].body.get('file')).toBeInstanceOf(Blob)
    expect(apiFetch.mock.calls[2][0]).toBe('/media/complete-upload?uploadId=upload-1')
  })

  it('restores completed server chunks as one-based parts', async () => {
    const apiFetch = vi.fn().mockResolvedValue([0, 2])
    const session = await new ChunkUploadTransport({ apiFetch }).check({ filename: 'test.mp4', totalParts: 3, sessionId: 'upload-1' })
    expect(session.completedParts).toEqual([1, 3])
    expect(apiFetch).toHaveBeenCalledWith('/media/upload-status?uploadId=upload-1', expect.any(Object))
  })

  it('cancels a session through the server cleanup API', async () => {
    const apiFetch = vi.fn().mockResolvedValue(null)
    await new ChunkUploadTransport({ apiFetch }).abort('upload-1')
    expect(apiFetch).toHaveBeenCalledWith('/media/upload?uploadId=upload-1', { method: 'DELETE' })
  })
})
