import axios, { AxiosError } from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

/** 提取 FastAPI 错误 detail（可能是字符串或对象）为可读文本 */
export function errorDetail(error: unknown): string {
  const err = error as AxiosError<{ detail?: unknown }>
  const detail = err.response?.data?.detail
  if (detail == null) return err.message || '请求失败'
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail)
  } catch {
    return String(detail)
  }
}

export default client
