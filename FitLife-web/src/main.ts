// 演示构建专用：必须在其它模块之前求值，先把演示标记写入 localStorage，
// 这样 http.ts 里的 mock 数据层才能在导入时被安装。正式构建时该模块为空操作。
import './demo/seed'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import router from './router'
import App from './App.vue'
import './style.css'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')