import { describe, expect, it, vi } from 'vitest'
import { pollAnalysisTask } from './taskPolling'

describe('pollAnalysisTask', () => {
  it('continues through active states and returns the success state', async () => {
    const apiFetch = vi.fn()
      .mockResolvedValueOnce({ taskId: 'task-1', status: 'PENDING', progress: 0 })
      .mockResolvedValueOnce({ taskId: 'task-1', status: 'RUNNING', progress: 40 })
      .mockResolvedValueOnce({ taskId: 'task-1', status: 'SUCCESS', progress: 100 })
    const onUpdate = vi.fn()

    const result = await pollAnalysisTask({
      taskId: 'task-1',
      apiFetch,
      intervalMs: 0,
      onUpdate
    })

    expect(result.status).toBe('SUCCESS')
    expect(apiFetch).toHaveBeenCalledTimes(3)
    expect(apiFetch).toHaveBeenNthCalledWith(
      1,
      '/tasks/task-1',
      expect.objectContaining({ signal: expect.any(AbortSignal) })
    )
    expect(onUpdate).toHaveBeenLastCalledWith(result)
  })

  it.each(['FAILED', 'DEAD', 'CANCELLED'])(
    'stops immediately at terminal state %s',
    async (status) => {
      const apiFetch = vi.fn().mockResolvedValue({
        taskId: 'task-1',
        status,
        errorMessage: 'stopped'
      })

      const result = await pollAnalysisTask({
        taskId: 'task-1',
        apiFetch,
        intervalMs: 0
      })

      expect(result.status).toBe(status)
      expect(apiFetch).toHaveBeenCalledTimes(1)
    }
  )
})
