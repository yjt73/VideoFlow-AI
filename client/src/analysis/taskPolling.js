const ACTIVE_STATES = new Set(['PENDING', 'QUEUED', 'RUNNING', 'RETRYING'])
const TERMINAL_STATES = new Set(['SUCCESS', 'FAILED', 'DEAD', 'CANCELLED'])

export const pollAnalysisTask = async ({
  taskId,
  apiFetch,
  intervalMs = 2000,
  onUpdate = () => {},
  signal
}) => {
  if (!taskId) throw new Error('缺少任务编号')

  const controller = new AbortController()
  const forwardAbort = () => controller.abort(signal?.reason)
  if (signal?.aborted) {
    forwardAbort()
  } else {
    signal?.addEventListener('abort', forwardAbort, { once: true })
  }

  try {
    while (!controller.signal.aborted) {
      const task = await apiFetch(`/tasks/${taskId}`, {
        signal: controller.signal
      })
      onUpdate(task)
      if (TERMINAL_STATES.has(task.status)) return task
      if (!ACTIVE_STATES.has(task.status)) {
        throw new Error(`未知任务状态: ${task.status}`)
      }
      await wait(intervalMs, controller.signal)
    }
    throw abortError()
  } finally {
    signal?.removeEventListener('abort', forwardAbort)
  }
}

const wait = (milliseconds, signal) => new Promise((resolve, reject) => {
  if (signal.aborted) {
    reject(abortError())
    return
  }
  const timer = setTimeout(resolve, milliseconds)
  signal.addEventListener('abort', () => {
    clearTimeout(timer)
    reject(abortError())
  }, { once: true })
})

const abortError = () => new DOMException('任务轮询已取消', 'AbortError')
