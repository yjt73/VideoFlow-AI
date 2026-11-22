const ACTIVE_STATES = new Set(['QUEUED', 'PROCESSING'])
const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED'])

export const pollAnalysisTask = async ({
  mediaId,
  goal,
  apiFetch,
  intervalMs = 2000,
  onUpdate = () => {},
  signal
}) => {
  if (!mediaId || !goal) throw new Error('缺少媒体编号或分析目标')

  const controller = new AbortController()
  const forwardAbort = () => controller.abort(signal?.reason)
  if (signal?.aborted) {
    forwardAbort()
  } else {
    signal?.addEventListener('abort', forwardAbort, { once: true })
  }

  try {
    let notStartedPolls = 0
    while (!controller.signal.aborted) {
      const params = new URLSearchParams({ id: String(mediaId), goal })
      const task = await apiFetch(`/analysis/analysis-status?${params}`, {
        signal: controller.signal
      })
      onUpdate(task)
      if (TERMINAL_STATES.has(task.state)) return task
      if (task.state === 'NOT_STARTED') {
        if (++notStartedPolls >= 3) throw new Error('分析任务未启动，请重试')
        await wait(intervalMs, controller.signal)
        continue
      }
      notStartedPolls = 0
      if (!ACTIVE_STATES.has(task.state)) {
        throw new Error(`未知任务状态: ${task.state}`)
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
