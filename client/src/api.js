const API_BASE_URL = 'http://localhost:9090'

export const getToken = () => localStorage.getItem('authToken')

export const setToken = (token) => localStorage.setItem('authToken', token)

export const clearToken = () => localStorage.removeItem('authToken')

export const apiFetch = async (path, options = {}) => {
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  return fetch(`${API_BASE_URL}${path}`, { ...options, headers })
}
