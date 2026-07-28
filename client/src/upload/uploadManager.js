const DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024
const RETRY_DELAYS_MS = [1000, 2000, 4000, 8000, 15000, 30000, 30000, 30000]

export const UPLOAD_STATES = Object.freeze({
  IDLE: 'idle',
  HASHING: 'hashing',
  UPLOADING: 'uploading',
  RETRYING: 'retrying',
  PAUSED: 'paused',
  MERGING: 'merging',
  COMPLETED: 'completed',
  FAILED: 'failed',
  CANCELLED: 'cancelled'
})

class FlowInterrupted extends Error {}

export class UploadManager {
  constructor({
    apiFetch,
    calculateFileMd5,
    calculateChunkMd5,
    storage = localStorage,
    concurrency = 3,
    chunkSize = DEFAULT_CHUNK_SIZE,
    sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
    onlineTarget = typeof window === 'undefined' ? null : window,
    onChange = () => {}
  }) {
    this.apiFetch = apiFetch
    this.calculateFileMd5 = calculateFileMd5
    this.calculateChunkMd5 = calculateChunkMd5
    this.storage = storage
    this.concurrency = concurrency
    this.chunkSize = chunkSize
    this.sleep = sleep
    this.onlineTarget = onlineTarget
    this.onChange = onChange
    this.controllers = new Set()
    this.runVersion = 0
    this.mergePromise = null
    this.pausedByUser = false
    this.reset()
    this.handleOnline = () => {
      if (!this.pausedByUser
        && [UPLOAD_STATES.PAUSED, UPLOAD_STATES.RETRYING].includes(this.state)) {
        void this.resume()
      }
    }
    this.onlineTarget?.addEventListener('online', this.handleOnline)
  }

  reset() {
    this.file = null
    this.userId = null
    this.fileMd5 = null
    this.uploadId = null
    this.totalChunks = 0
    this.uploadedChunks = new Set()
    this.state = UPLOAD_STATES.IDLE
    this.retryCount = 0
    this.hashProgress = 0
    this.progress = 0
    this.lastError = null
    this.resumeStage = 'uploading'
    this.deduplicated = false
    this.media = null
    this.emit()
  }

  get snapshot() {
    return {
      state: this.state,
      uploadId: this.uploadId,
      totalChunks: this.totalChunks,
      uploadedChunks: [...this.uploadedChunks].sort((a, b) => a - b),
      retryCount: this.retryCount,
      hashProgress: this.hashProgress,
      progress: this.progress,
      lastError: this.lastError,
      resumeStage: this.resumeStage,
      deduplicated: this.deduplicated,
      media: this.media
    }
  }

  async start(file, userId) {
    this.abortRequests()
    this.runVersion += 1
    const version = this.runVersion
    this.file = file
    this.userId = userId
    this.totalChunks = Math.ceil(file.size / this.chunkSize)
    this.uploadedChunks = new Set()
    this.retryCount = 0
    this.progress = 0
    this.hashProgress = 0
    this.lastError = null
    this.deduplicated = false
    this.media = null
    this.pausedByUser = false
    this.setState(UPLOAD_STATES.HASHING)

    try {
      this.fileMd5 = await this.calculateFileMd5(file, (progress) => {
        if (version !== this.runVersion) return
        this.hashProgress = progress
        this.emit()
      })
      this.ensureCurrent(version)
      const check = await this.retryOperation(
        () => this.checkUpload(version),
        version,
        'uploading'
      )
      return await this.continueFromCheck(check, version)
    } catch (error) {
      return this.handleTerminalError(error, version)
    }
  }

  pause() {
    if (![UPLOAD_STATES.HASHING, UPLOAD_STATES.UPLOADING,
      UPLOAD_STATES.RETRYING, UPLOAD_STATES.MERGING].includes(this.state)) return
    this.pausedByUser = true
    this.resumeStage = this.state === UPLOAD_STATES.MERGING ? 'merging' : 'uploading'
    this.runVersion += 1
    this.abortRequests()
    this.setState(UPLOAD_STATES.PAUSED)
  }

  async resume() {
    if (!this.file || !this.fileMd5) return null
    this.pausedByUser = false
    this.abortRequests()
    this.runVersion += 1
    const version = this.runVersion
    this.setState(UPLOAD_STATES.RETRYING)
    try {
      const check = await this.retryOperation(
        () => this.checkUpload(version),
        version,
        this.resumeStage
      )
      return await this.continueFromCheck(check, version)
    } catch (error) {
      return this.handleTerminalError(error, version)
    }
  }

  async cancel() {
    this.pausedByUser = true
    this.runVersion += 1
    this.abortRequests()
    const uploadId = this.uploadId
    if (uploadId) {
      try {
        await this.apiFetch(`/media/upload?uploadId=${encodeURIComponent(uploadId)}`, {
          method: 'DELETE'
        })
      } catch (error) {
        if (!isExpiredUpload(error)) throw error
      }
    }
    this.clearStoredUpload()
    this.setState(UPLOAD_STATES.CANCELLED)
  }

  dispose() {
    this.runVersion += 1
    this.abortRequests()
    this.onlineTarget?.removeEventListener('online', this.handleOnline)
  }

  async checkUpload(version) {
    const storedUploadId = this.storage.getItem(this.storageKey())
    const request = {
      filename: this.file.name,
      fileMd5: this.fileMd5,
      totalChunks: this.totalChunks,
      uploadId: storedUploadId || undefined
    }
    try {
      return await this.request('/media/check-upload', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      }, version)
    } catch (error) {
      if (!storedUploadId || !isExpiredUpload(error)) throw error
      this.clearStoredUpload()
      delete request.uploadId
      return this.request('/media/check-upload', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      }, version)
    }
  }

  async continueFromCheck(check, version) {
    this.ensureCurrent(version)
    if (check.state === 'COMPLETED') {
      this.deduplicated = Boolean(check.deduplicated)
      this.media = check.media
      this.progress = 100
      this.clearStoredUpload()
      this.setState(UPLOAD_STATES.COMPLETED)
      return check.media
    }

    this.uploadId = check.uploadId
    this.storage.setItem(this.storageKey(), this.uploadId)
    this.uploadedChunks = new Set(check.uploadedChunks ?? [])
    this.updateProgress()

    if (this.uploadedChunks.size >= this.totalChunks || this.resumeStage === 'merging') {
      return this.merge(version)
    }
    return this.uploadMissingChunks(version)
  }

  async uploadMissingChunks(version) {
    this.resumeStage = 'uploading'
    this.setState(UPLOAD_STATES.UPLOADING)
    const missing = Array.from({ length: this.totalChunks }, (_, index) => index)
      .filter((index) => !this.uploadedChunks.has(index))
    let cursor = 0

    const worker = async () => {
      while (cursor < missing.length) {
        this.ensureCurrent(version)
        const index = missing[cursor]
        cursor += 1
        await this.retryOperation(() => this.uploadChunk(index, version), version, 'uploading')
        this.ensureCurrent(version)
        this.uploadedChunks.add(index)
        this.retryCount = 0
        this.updateProgress()
      }
    }

    try {
      await Promise.all(Array.from(
        { length: Math.min(this.concurrency, missing.length) },
        () => worker()
      ))
      this.ensureCurrent(version)
      return await this.merge(version)
    } catch (error) {
      if (error instanceof FlowInterrupted) return null
      throw error
    }
  }

  async uploadChunk(index, version) {
    const start = index * this.chunkSize
    const chunk = this.file.slice(start, Math.min(this.file.size, start + this.chunkSize))
    const chunkMd5 = await this.calculateChunkMd5(chunk)
    this.ensureCurrent(version)
    const formData = new FormData()
    formData.append('uploadId', this.uploadId)
    formData.append('chunkIndex', String(index))
    formData.append('totalChunks', String(this.totalChunks))
    formData.append('chunkMd5', chunkMd5)
    formData.append('file', chunk)
    return this.request('/media/upload-chunk', {
      method: 'POST',
      body: formData
    }, version)
  }

  async merge(version) {
    this.resumeStage = 'merging'
    if (this.mergePromise) return this.mergePromise
    this.setState(UPLOAD_STATES.MERGING)
    this.mergePromise = this.retryOperation(
      () => this.request(
        `/media/complete-upload?uploadId=${encodeURIComponent(this.uploadId)}`,
        { method: 'POST' },
        version
      ),
      version,
      'merging'
    ).then((media) => {
      this.ensureCurrent(version)
      this.media = media
      this.progress = 100
      this.clearStoredUpload()
      this.setState(UPLOAD_STATES.COMPLETED)
      return media
    }).catch((error) => {
      if (error instanceof FlowInterrupted) return null
      throw error
    }).finally(() => {
      this.mergePromise = null
    })
    return this.mergePromise
  }

  async retryOperation(operation, version, stage) {
    for (let attempt = 0; attempt < RETRY_DELAYS_MS.length; attempt += 1) {
      try {
        return await operation()
      } catch (error) {
        this.ensureCurrent(version)
        if (!isRetryable(error)) throw error
        this.lastError = error.message
        this.retryCount = attempt + 1
        this.resumeStage = stage
        if (this.retryCount >= RETRY_DELAYS_MS.length) {
          this.pausedByUser = false
          this.setState(UPLOAD_STATES.PAUSED)
          throw new FlowInterrupted()
        }
        this.setState(UPLOAD_STATES.RETRYING)
        await this.sleep(RETRY_DELAYS_MS[attempt])
        this.ensureCurrent(version)
        this.setState(stage === 'merging'
          ? UPLOAD_STATES.MERGING
          : UPLOAD_STATES.UPLOADING)
      }
    }
    throw new FlowInterrupted()
  }

  async request(path, options, version) {
    this.ensureCurrent(version)
    const controller = new AbortController()
    this.controllers.add(controller)
    try {
      return await this.apiFetch(path, { ...options, signal: controller.signal })
    } finally {
      this.controllers.delete(controller)
    }
  }

  abortRequests() {
    for (const controller of this.controllers) controller.abort()
    this.controllers.clear()
  }

  ensureCurrent(version) {
    if (version !== this.runVersion
      || [UPLOAD_STATES.CANCELLED, UPLOAD_STATES.PAUSED].includes(this.state)) {
      throw new FlowInterrupted()
    }
  }

  handleTerminalError(error, version) {
    if (error instanceof FlowInterrupted || version !== this.runVersion) return null
    this.lastError = error instanceof Error ? error.message : String(error)
    this.setState(UPLOAD_STATES.FAILED)
    return null
  }

  storageKey() {
    return `upload:${this.userId}:${this.fileMd5}`
  }

  clearStoredUpload() {
    if (this.userId != null && this.fileMd5) {
      this.storage.removeItem(this.storageKey())
    }
  }

  updateProgress() {
    this.progress = this.totalChunks === 0
      ? 0
      : Math.round((this.uploadedChunks.size / this.totalChunks) * 100)
    this.emit()
  }

  setState(state) {
    this.state = state
    this.emit()
  }

  emit() {
    this.onChange(this.snapshot)
  }
}

const isExpiredUpload = (error) => error?.status === 404

const isRetryable = (error) => {
  if (error?.name === 'AbortError') return false
  if (error?.retryable) return true
  if (!error?.status) return true
  return error.status === 503 || error.status >= 500
}
