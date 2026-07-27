const API_BASE_URL = 'http://localhost:9090'

export const getToken = () => localStorage.getItem('authToken')

export const setToken = (token) => localStorage.setItem('authToken', token)

export const clearToken = () => localStorage.removeItem('authToken')

export const apiFetch = async (path, options = {}) => {
  const { raw = false, ...fetchOptions } = options
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(`${API_BASE_URL}${path}`, { ...fetchOptions, headers })
  const isJson = response.headers.get('content-type')?.includes('application/json')
  const payload = isJson ? await response.json() : null

  if (!response.ok || (payload && payload.code >= 400)) {
    const error = new Error(payload?.message || `请求失败 (${response.status})`)
    error.status = response.status
    error.code = payload?.code
    error.requestId = payload?.requestId
    throw error
  }

  if (raw || !isJson) return response
  return payload.data
}
