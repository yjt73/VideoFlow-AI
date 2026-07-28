import SparkMD5 from 'spark-md5'

const READ_SIZE = 2 * 1024 * 1024

self.addEventListener('message', async ({ data }) => {
  const file = data?.file
  if (!file) {
    self.postMessage({ type: 'error', message: '未提供待计算文件' })
    return
  }

  try {
    const spark = new SparkMD5.ArrayBuffer()
    if (file.size === 0) {
      self.postMessage({ type: 'progress', progress: 100 })
    }
    for (let offset = 0; offset < file.size; offset += READ_SIZE) {
      const end = Math.min(file.size, offset + READ_SIZE)
      spark.append(await file.slice(offset, end).arrayBuffer())
      self.postMessage({
        type: 'progress',
        progress: Math.round((end / file.size) * 100)
      })
    }
    self.postMessage({ type: 'done', md5: spark.end() })
  } catch (error) {
    self.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : '文件哈希计算失败'
    })
  }
})
