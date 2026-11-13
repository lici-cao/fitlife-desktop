import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { viteSingleFile } from 'vite-plugin-singlefile'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 演示构建：把 JS/CSS/字体全部内联成一个 index.html。
  // 分享域名对静态资源的请求需要带上访问码 cookie，单文件可彻底绕开这个依赖。
  const isDemo = env.VITE_DEMO === '1'

  return {
    plugins: [vue(), ...(isDemo ? [viteSingleFile()] : [])],
    build: {
      // 演示构建输出到独立目录，避免覆盖正式产物
      outDir: env.VITE_OUT_DIR || 'dist',
      // 桌面版内嵌进 jar，sourcemap 只会增大体积
      sourcemap: env.VITE_SOURCEMAP === '1',
      ...(isDemo ? { cssCodeSplit: false, assetsInlineLimit: 100_000_000 } : {})
    },
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  }
})
