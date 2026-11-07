/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_API_URL?: string
  /** '1' 表示演示构建：启用本地 mock 数据层，不依赖后端 */
  readonly VITE_DEMO?: string
  /** 'hash' 表示使用 hash 路由，便于托管在纯静态空间 */
  readonly VITE_ROUTER_MODE?: string
  /** 演示构建的输出目录 */
  readonly VITE_OUT_DIR?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
