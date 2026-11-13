/**
 * HTTP 请求工具类
 * 基于 Axios 封装，支持拦截器、超时处理、统一错误处理、幂等键
 */

import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig, AxiosRequestHeaders } from 'axios'
import { installDemoMock } from './demo-mock'

// 开发环境使用相对路径（由Vite代理转发），生产环境使用绝对路径
const BASE_URL = import.meta.env.VITE_APP_API_URL || '/api'

/** 供 fetch（SSE 流式对话）复用的接口前缀 */
export const API_BASE_URL = BASE_URL

/** 写操作默认携带的幂等键请求头，与后端 IdempotencyAspect 约定一致 */
export const IDEMPOTENCY_HEADER = 'Idempotency-Key'

/**
 * 生成幂等键。
 *
 * 同一份业务意图必须复用同一个 Key 才叫「幂等」：如果调用方自己重试（例如网络抖动后
 * 重新发一次生成计划），应把第一次生成的 Key 存下来传给重试请求，而不是重新调这个函数。
 */
export function newIdempotencyKey(): string {
  const webCrypto = globalThis.crypto
  if (webCrypto && typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID()
  }
  return `idem-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/** 不参与自动幂等的路径：登录/注册本身不该被「回放」 */
const AUTH_PATH = '/auth/'

function isAuthRequest(url?: string): boolean {
  return !!url && url.includes(AUTH_PATH)
}

// 创建 Axios 实例
const axiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 40000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/**
 * 请求拦截器
 * 1. 注入 Bearer Token；
 * 2. 为写操作自动生成幂等键（可用 skipIdempotency: true 关闭）；
 * 3. 不再打印完整请求对象——原实现会把 Authorization 头打进控制台。
 */
axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    config.headers = config.headers || ({} as AxiosRequestHeaders)

    const token = localStorage.getItem('fitlife_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }

    const method = (config.method || 'get').toLowerCase()
    const isWrite = method === 'post' || method === 'put' || method === 'patch' || method === 'delete'
    const skip = (config as any).skipIdempotency === true || isAuthRequest(config.url)
    if (isWrite && !skip && !config.headers[IDEMPOTENCY_HEADER]) {
      config.headers[IDEMPOTENCY_HEADER] = newIdempotencyKey()
    }

    return config
  },
  (error: AxiosError) => {
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器
 * 401 才清登录态；403 是「登录了但没权限」，清 token 反而会把人踢回登录页。
 * 登录/注册接口的 401 也不跳转，否则输错密码会整页刷新、提示文案丢失。
 */
axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => {
    return response
  },
  (error: AxiosError) => {
    const status = error.response?.status
    const url = error.config?.url || ''

    if (status === 401) {
      if (isAuthRequest(url)) {
        return Promise.reject(error)
      }
      localStorage.removeItem('fitlife_token')
      localStorage.removeItem('fitlife_userId')
      window.location.href = '/login'
      return Promise.reject(new Error('登录状态已失效，请重新登录'))
    }

    if (status === 403) {
      return Promise.reject(error)
    }

    return Promise.reject(error)
  }
)

/**
 * 发送请求
 * @param config - 请求配置
 * @returns {Promise}
 */
function request<T = any>(config: Record<string, any>): Promise<AxiosResponse<T>> {
  return axiosInstance(config)
}

/**
 * GET 请求
 * @param url - 请求地址
 * @param params - URL参数
 * @param options - 其他选项
 * @returns {Promise}
 */
function get<T = any>(
  url: string,
  params?: Record<string, any>,
  options?: Record<string, any>
): Promise<AxiosResponse<T>> {
  return axiosInstance.get(url, {
    params,
    ...options
  })
}

/**
 * POST 请求
 * @param url - 请求地址
 * @param data - 请求体数据
 * @param options - 其他选项（幂等键默认自动生成）
 * @returns {Promise}
 */
function post<T = any>(
  url: string,
  data?: Record<string, any>,
  options?: Record<string, any>
): Promise<AxiosResponse<T>> {
  return axiosInstance.post(url, data, options)
}

/**
 * PUT 请求
 * @param url - 请求地址
 * @param data - 请求体数据
 * @param options - 其他选项
 * @returns {Promise}
 */
function put<T = any>(
  url: string,
  data?: Record<string, any>,
  options?: Record<string, any>
): Promise<AxiosResponse<T>> {
  return axiosInstance.put(url, data, options)
}

/**
 * DELETE 请求
 * @param url - 请求地址
 * @param options - 其他选项
 * @returns {Promise}
 */
function del<T = any>(
  url: string,
  options?: Record<string, any>
): Promise<AxiosResponse<T>> {
  return axiosInstance.delete(url, options)
}

// 导出 HTTP 工具
export const http = {
  request,
  get,
  post,
  put,
  delete: del,
  // 暴露 axios 实例供特殊使用
  instance: axiosInstance
}

// 演示模式：开发环境由 /demo.html 写入标记；演示构建（VITE_DEMO=1）由 main.ts 预置。
// 正式启动后端、或普通 npm run build 的产物中，此分支恒不成立，不会有任何影响。
const demoEnabled = import.meta.env.DEV || import.meta.env.VITE_DEMO === '1'
if (demoEnabled && localStorage.getItem('fitlife_demo') === '1') {
  installDemoMock(axiosInstance)
}

export default http