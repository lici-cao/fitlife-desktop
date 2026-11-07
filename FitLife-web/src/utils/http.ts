/**
 * HTTP 请求工具类
 * 基于 Axios 封装，支持拦截器、超时处理、统一错误处理
 */

import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig, AxiosRequestHeaders } from 'axios'
import { installDemoMock } from './demo-mock'

// 开发环境使用相对路径（由Vite代理转发），生产环境使用绝对路径
const BASE_URL = import.meta.env.VITE_APP_API_URL || '/api'

/** 供 fetch（SSE 流式对话）复用的接口前缀 */
export const API_BASE_URL = BASE_URL

// 桌面版默认 hash 路由，登录跳转必须带 #，否则会命中后端 404
const ROUTER_HASH_MODE = import.meta.env.VITE_ROUTER_MODE !== 'history'
const LOGIN_PATH = ROUTER_HASH_MODE ? '/#/login' : '/login'

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
 * 在发送请求之前做些什么
 */
axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 添加 Token
    const token = localStorage.getItem('fitlife_token')
    if (token) {
      config.headers = config.headers || {} as AxiosRequestHeaders
      config.headers['Authorization'] = `Bearer ${token}`
    }
    console.debug('[HTTP] Request:', config)
    return config
  },
  (error: AxiosError) => {
    console.error('[HTTP] Request Error:', error)
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器
 * 对响应数据做些什么
 */
axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => {
    console.debug('[HTTP] Response:', response)
    return response
  },
  (error: AxiosError) => {
    console.error('[HTTP] Response Error:', error)

    // Token过期处理
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('fitlife_token')
      localStorage.removeItem('fitlife_userId')
      window.location.href = LOGIN_PATH
      return Promise.reject(new Error('Token已过期，请重新登录'))
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
 * @param options - 其他选项
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