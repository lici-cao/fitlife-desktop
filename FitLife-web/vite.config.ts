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
    // 桌面版产物被内嵌到 Spring Boot 的 static 根目录，资源引用要相对根路径，
    // 想部署到子路径（如 /fitlife/）时用 VITE_BASE 覆盖。
    base: env.VITE_BASE || '/',
    plugins: [vue(), ...(isDemo ? [viteSingleFile()] : [])],
    build: {
      // 演示构建输出到独立目录，避免覆盖正式产物
      outDir: env.VITE_OUT_DIR || 'dist',
      // 桌面版不生成 sourcemap，减小内嵌后的 jar 体积
      sourcemap: env.VITE_SOURCEMAP === '1',
      chunkSizeWarningLimit: 1500,
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
