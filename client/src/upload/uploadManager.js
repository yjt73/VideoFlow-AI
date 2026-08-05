const DEFAULT_PART_SIZE = 5 * 1024 * 1024
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
    transport,
    calculateFileMd5,
    storage = localStorage,
    concurrency = 3,
    chunkSize = DEFAULT_PART_SIZE,
    progressBatchSize = 5,
    urlBatchSize = 12,
    sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
    onlineTarget = typeof window === 'undefined' ? null : window,
    onChange = () => {}
  }) {
    this.transport = transport
    this.calculateFileMd5 = calculateFileMd5
    this.storage = storage
    this.concurrency = concurrency
    this.chunkSize = chunkSize
    this.progressBatchSize = progressBatchSize
    this.urlBatchSize = urlBatchSize
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
    this.sessionId = null
    this.totalParts = 0
    this.partSize = this.chunkSize
    this.completedParts = new Map()
    this.pendingProgress = new Map()
    this.progressFlushPromise = null
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
    const completedParts = this.sortedCompletedParts()
    return {
      state: this.state,
      sessionId: this.sessionId,
      totalParts: this.totalParts,
      uploadedChunks: completedParts,
      completedParts,
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
    this.fileMd5 = null
    this.sessionId = null
    this.totalParts = Math.ceil(file.size / this.chunkSize)
    this.partSize = this.chunkSize
    this.completedParts = new Map()
    this.pendingProgress = new Map()
    this.progressFlushPromise = null
    this.retryCount = 0
    this.progress = 0
    this.hashProgress = 0
    this.lastError = null
    this.deduplicated = false
    this.media = null
    this.pausedByUser = false
    this.setState(UPLOAD_STATES.HASHING)

    try {
      const controller = this.createController()
      try {
        this.fileMd5 = await this.calculateFileMd5(file, (progress) => {
          if (version !== this.runVersion) return
          this.hashProgress = progress
          this.emit()
        }, { signal: controller.signal })
      } finally {
        this.controllers.delete(controller)
      }
      this.ensureCurrent(version)
      this.restoreStoredSession()
      const check = await this.retryOperation(
        () => this.withController(
          (signal) => this.transport.check(this.metadata(), signal),
          version
        ),
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
    if (!this.file) return null
    if (!this.fileMd5) return this.start(this.file, this.userId)
    this.pausedByUser = false
    this.abortRequests()
    this.runVersion += 1
    const version = this.runVersion
    this.setState(UPLOAD_STATES.RETRYING)
    try {
      const check = await this.retryOperation(
        () => this.withController(
          (signal) => this.transport.check(this.metadata(), signal),
          version
        ),
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
    const sessionId = this.sessionId
    if (sessionId) {
      try {
        await this.transport.abort(sessionId)
      } catch {
        // 取消必须优先释放本地文件与页面状态；服务端会在上传会话过期后清理残留。
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

  metadata() {
    return {
      filename: this.file.name,
      contentHash: this.fileMd5,
      sizeBytes: this.file.size,
      contentType: this.file.type || 'application/octet-stream',
      totalParts: this.totalParts,
      ...(this.sessionId ? { sessionId: this.sessionId } : {})
    }
  }

  async continueFromCheck(check, version) {
    this.ensureCurrent(version)
    if (check.state === 'COMPLETED') {
      this.deduplicated = true
      this.media = check.media
      this.progress = 100
      this.clearStoredUpload()
      this.setState(UPLOAD_STATES.COMPLETED)
      return check.media
    }

    this.sessionId = check.sessionId
    this.partSize = check.partSize
    this.totalParts = check.totalParts
    this.applyCompletedParts(check.completedParts)
    this.persistUpload()
    this.updateProgress()

    if (this.completedParts.size >= this.totalParts || this.resumeStage === 'merging') {
      return this.complete(version)
    }
    return this.uploadMissingParts(version)
  }

  async uploadMissingParts(version) {
    this.resumeStage = 'uploading'
    this.setState(UPLOAD_STATES.UPLOADING)
    const missing = Array.from({ length: this.totalParts }, (_, index) => index + 1)
      .filter((partNumber) => !this.completedParts.has(partNumber))

    try {
      for (let offset = 0; offset < missing.length; offset += this.urlBatchSize) {
        const batch = missing.slice(offset, offset + this.urlBatchSize)
        const signedParts = await this.retryOperation(
          () => this.withController(
            (signal) => this.transport.presignParts(this.sessionId, batch, signal),
            version
          ),
          version,
          'uploading'
        )
        const signedUrls = new Map(signedParts.map((part) => [part.partNumber, part.url]))
        if (batch.some((partNumber) => !signedUrls.has(partNumber))) {
          throw new Error('服务端返回的分片预签名地址不完整')
        }
        await this.uploadBatch(batch, signedUrls, version)
      }
      this.ensureCurrent(version)
      return await this.complete(version)
    } catch (error) {
      if (error instanceof FlowInterrupted) return null
      throw error
    }
  }

  async uploadBatch(partNumbers, signedUrls, version) {
    let cursor = 0
    const worker = async () => {
      while (cursor < partNumbers.length) {
        this.ensureCurrent(version)
        const partNumber = partNumbers[cursor]
        cursor += 1
        await this.retryOperation(
          () => this.uploadPart(partNumber, version, signedUrls.get(partNumber)),
          version,
          'uploading'
        )
        this.ensureCurrent(version)
        this.completedParts.set(partNumber, true)
        this.pendingProgress.set(partNumber, true)
        this.persistUpload()
        this.updateProgress()
        await this.flushProgress(version)
      }
    }
    await Promise.all(Array.from(
      { length: Math.min(this.concurrency, partNumbers.length) },
      () => worker()
    ))
  }

  uploadPart(partNumber, version, signedUrl) {
    const start = (partNumber - 1) * this.partSize
    const blob = this.file.slice(start, Math.min(this.file.size, start + this.partSize))
    return this.withController(
      (signal) => this.transport.uploadPart(
        this.sessionId,
        partNumber,
        blob,
        signal,
        signedUrl
      ),
      version
    )
  }

  async complete(version) {
    this.resumeStage = 'merging'
    if (this.mergePromise) return this.mergePromise
    this.mergePromise = (async () => {
      await this.flushProgress(version, true)
      this.ensureCurrent(version)
      this.setState(UPLOAD_STATES.MERGING)
      return this.retryOperation(
        () => this.withController(
          (signal) => this.transport.complete(
            this.sessionId,
            signal
          ),
          version
        ),
        version,
        'merging'
      )
    })().then((media) => {
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
    while (true) {
      try {
        const result = await operation()
        this.retryCount = 0
        return result
      } catch (error) {
        this.ensureCurrent(version)
        if (!isRetryable(error)) throw error
        this.lastError = error.message
        this.retryCount += 1
        const failureCount = this.retryCount
        this.resumeStage = stage
        if (failureCount >= RETRY_DELAYS_MS.length) {
          this.pausedByUser = false
          this.abortRequests()
          this.setState(UPLOAD_STATES.PAUSED)
          throw new FlowInterrupted()
        }
        this.setState(UPLOAD_STATES.RETRYING)
        await this.sleep(RETRY_DELAYS_MS[failureCount - 1])
        this.ensureCurrent(version)
        this.setState(stage === 'merging'
          ? UPLOAD_STATES.MERGING
          : UPLOAD_STATES.UPLOADING)
      }
    }
  }

  async withController(operation, version) {
    this.ensureCurrent(version)
    const controller = this.createController()
    try {
      return await operation(controller.signal)
    } finally {
      this.controllers.delete(controller)
    }
  }

  createController() {
    const controller = new AbortController()
    this.controllers.add(controller)
    return controller
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
    return `multipart-upload:${this.userId}:${this.fileMd5}`
  }

  persistUpload() {
    this.storage.setItem(this.storageKey(), JSON.stringify({
      sessionId: this.sessionId,
      filename: this.file.name
    }))
  }

  restoreStoredSession() {
    const stored = this.storage.getItem(this.storageKey())
    if (!stored) return
    try {
      const parsed = JSON.parse(stored)
      if (typeof parsed.sessionId === 'string'
        && parsed.sessionId
        && parsed.filename === this.file.name) {
        this.sessionId = parsed.sessionId
      }
    } catch {
      this.clearStoredUpload()
    }
  }

  applyCompletedParts(parts) {
    this.completedParts = new Map()
    if (!Array.isArray(parts)) return
    for (const part of parts) {
      const partNumber = Number.isInteger(part) ? part : part?.partNumber
      if (Number.isInteger(partNumber)
        && partNumber >= 1
        && partNumber <= this.totalParts) {
        this.completedParts.set(partNumber, true)
      }
    }
  }

  async flushProgress(version, force = false) {
    if (!force && this.pendingProgress.size < this.progressBatchSize) return
    if (this.progressFlushPromise) {
      await this.progressFlushPromise
      if (force && this.pendingProgress.size > 0) {
        return this.flushProgress(version, true)
      }
      return
    }
    if (this.pendingProgress.size === 0) return

    const partNumbers = [...this.pendingProgress.keys()].sort((left, right) => left - right)
    for (const partNumber of partNumbers) this.pendingProgress.delete(partNumber)

    this.progressFlushPromise = this.retryOperation(
      () => this.withController(
        (signal) => this.transport.reportProgress(this.sessionId, partNumbers, signal),
        version
      ),
      version,
      'uploading'
    ).catch((error) => {
      for (const partNumber of partNumbers) this.pendingProgress.set(partNumber, true)
      throw error
    }).finally(() => {
      this.progressFlushPromise = null
    })
    return this.progressFlushPromise
  }

  clearStoredUpload() {
    if (this.userId != null && this.fileMd5) {
      this.storage.removeItem(this.storageKey())
    }
  }

  sortedCompletedParts() {
    return [...this.completedParts.keys()].sort((left, right) => left - right)
  }

  updateProgress() {
    this.progress = this.totalParts === 0
      ? 0
      : Math.round((this.completedParts.size / this.totalParts) * 100)
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

const isRetryable = (error) => {
  if (error?.name === 'AbortError') return false
  if (error?.retryable) return true
  if (!error?.status) return true
  return error.status === 429 || error.status === 503 || error.status >= 500
}
