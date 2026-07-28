import SparkMD5 from 'spark-md5'

const createMd5Worker = () => new Worker(
  new URL('./md5.worker.js', import.meta.url),
  { type: 'module' }
)

export const calculateFileMd5 = (
  file,
  onProgress = () => {},
  options = {}
) => new Promise((resolve, reject) => {
  const workerFactory = typeof options === 'function'
    ? options
    : options.workerFactory ?? createMd5Worker
  const signal = typeof options === 'function' ? null : options.signal
  const worker = workerFactory()
  let settled = false

  const cleanup = () => {
    signal?.removeEventListener('abort', handleAbort)
    worker.removeEventListener('message', handleMessage)
    worker.removeEventListener('error', handleError)
    worker.terminate()
  }

  const handleMessage = ({ data }) => {
    if (settled) return
    if (data?.type === 'progress') {
      onProgress(data.progress)
      return
    }
    if (data?.type === 'done') {
      settled = true
      cleanup()
      resolve(data.md5)
      return
    }
    if (data?.type === 'error') {
      settled = true
      cleanup()
      reject(new Error(data.message || '文件哈希计算失败'))
    }
  }

  const handleError = (event) => {
    if (settled) return
    settled = true
    cleanup()
    reject(new Error(event?.message || '文件哈希计算失败'))
  }

  const handleAbort = () => {
    if (settled) return
    settled = true
    cleanup()
    reject(new DOMException('文件哈希计算已取消', 'AbortError'))
  }

  worker.addEventListener('message', handleMessage)
  worker.addEventListener('error', handleError)
  if (signal?.aborted) {
    handleAbort()
    return
  }
  signal?.addEventListener('abort', handleAbort, { once: true })
  worker.postMessage({ file })
})

export const calculateChunkMd5 = async (blob) => {
  const buffer = await blob.arrayBuffer()
  return SparkMD5.ArrayBuffer.hash(buffer)
}
