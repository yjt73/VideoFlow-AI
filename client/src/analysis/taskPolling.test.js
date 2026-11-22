import { describe, expect, it, vi } from 'vitest'
import { pollAnalysisTask } from './taskPolling'

describe('pollAnalysisTask', () => {
  it('polls the current analysis status API until completion', async () => {
    const apiFetch = vi.fn()
      .mockResolvedValueOnce({ state: 'QUEUED', message: '等待中' })
      .mockResolvedValueOnce({ state: 'PROCESSING', message: '分析中' })
      .mockResolvedValueOnce({ state: 'COMPLETED', result: '结果' })
    const result = await pollAnalysisTask({ mediaId: 7, goal: '分析视频', apiFetch, intervalMs: 0 })
    expect(result).toEqual({ state: 'COMPLETED', result: '结果' })
    expect(apiFetch).toHaveBeenCalledTimes(3)
    expect(apiFetch.mock.calls[0][0]).toBe('/analysis/analysis-status?id=7&goal=%E5%88%86%E6%9E%90%E8%A7%86%E9%A2%91')
  })

  it('stops on failed status', async () => {
    const apiFetch = vi.fn().mockResolvedValue({ state: 'FAILED', message: '失败' })
    expect(await pollAnalysisTask({ mediaId: 7, goal: '分析视频', apiFetch, intervalMs: 0 }))
      .toEqual({ state: 'FAILED', message: '失败' })
  })

  it('retries a transient not started status', async () => {
    const apiFetch = vi.fn()
      .mockResolvedValueOnce({ state: 'NOT_STARTED' })
      .mockResolvedValueOnce({ state: 'QUEUED' })
      .mockResolvedValueOnce({ state: 'COMPLETED', result: '结果' })
    expect(await pollAnalysisTask({ mediaId: 7, goal: '分析视频', apiFetch, intervalMs: 0 }))
      .toEqual({ state: 'COMPLETED', result: '结果' })
  })

  it('reports an analysis task that never starts', async () => {
    const apiFetch = vi.fn().mockResolvedValue({ state: 'NOT_STARTED' })
    await expect(pollAnalysisTask({ mediaId: 7, goal: '分析视频', apiFetch, intervalMs: 0 }))
      .rejects.toThrow('分析任务未启动，请重试')
    expect(apiFetch).toHaveBeenCalledTimes(3)
  })
})
