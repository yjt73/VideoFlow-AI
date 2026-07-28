import SparkMD5 from 'spark-md5'

const createMd5Worker = () => new Worker(
  new URL('./md5.worker.js', import.meta.url),
  { type: 'module' }
)

export const calculateFileMd5 = (
  file,
  onProgress = () => {},
  workerFactory = createMd5Worker
) => new Promise((resolve, reject) => {
  const worker = workerFactory()

  const cleanup = () => {
    worker.removeEventListener('message', handleMessage)
    worker.removeEventListener('error', handleError)
    worker.terminate()
  }

  const handleMessage = ({ data }) => {
    if (data?.type === 'progress') {
      onProgress(data.progress)
      return
    }
    if (data?.type === 'done') {
      cleanup()
      resolve(data.md5)
      return
    }
    if (data?.type === 'error') {
      cleanup()
      reject(new Error(data.message || '文件哈希计算失败'))
    }
  }

  const handleError = (event) => {
    cleanup()
    reject(new Error(event?.message || '文件哈希计算失败'))
  }

  worker.addEventListener('message', handleMessage)
  worker.addEventListener('error', handleError)
  worker.postMessage({ file })
})

export const calculateChunkMd5 = async (blob) => {
  const buffer = await blob.arrayBuffer()
  return SparkMD5.ArrayBuffer.hash(buffer)
}
