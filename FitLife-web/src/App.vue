<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from './stores/app'

const router = useRouter()
const route = useRoute()
const appStore = useAppStore()
const isCollapse = ref(false)

const menuItems = [
  { path: '/dashboard', title: '首页', icon: 'DataAnalysis' },
  { path: '/profile', title: '身体数据', icon: 'User' },
  { path: '/calculator', title: '热量计算', icon: 'Odometer' },
  { path: '/food', title: '饮食记录', icon: 'Food' },
  { path: '/exercise', title: '运动记录', icon: 'Coin' },
  { path: '/exercise-library', title: '动作库', icon: 'Collection' },
  { path: '/plan', title: '训练计划', icon: 'Calendar' },
  { path: '/ai-companion', title: 'AI健身搭子', icon: 'Bot' }
]

// 初始化应用状态
onMounted(() => {
  appStore.initFromStorage()
})

const handleMenuSelect = (path: string) => {
  router.push(path)
}

const handleLogout = () => {
  appStore.clearLoginStatus()
  ElMessage.success('退出成功')
  router.push('/login')
}

const handleDateChange = (date: string) => {
  if (date) {
    appStore.setDate(date)
    console.log('选择的日期:', date)
  }
}
</script>

<template>
  <el-container class="app-container">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon size="28"><Coin /></el-icon>
        <span v-if="!isCollapse">FitLife</span>
      </div>
      <el-menu
        :default-active="route.path"
        class="sidebar-menu"
        :collapse="isCollapse"
        @select="handleMenuSelect"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button text @click="isCollapse = !isCollapse">
            <el-icon size="20"><Fold v-if="!isCollapse" /><Expand v-else /></el-icon>
          </el-button>
        </div>
        <div class="header-right">
          <el-date-picker
            v-model="appStore.selectedDate"
            type="date"
            placeholder="选择日期"
            style="width: 160px"
            size="default"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            @change="handleDateChange"
          />
          <el-button text @click="handleLogout" class="logout-btn">
            <el-icon><Logout /></el-icon>
            <span>退出登录</span>
          </el-button>
        </div>
      </el-header>
      <el-main :class="['main-content', { 'main-flush': route.path === '/ai-companion' }]">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-container {
  height: 100vh;
}

.sidebar {
  background: #1a1a2e;
  color: #fff;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 20px;
  font-weight: bold;
  color: #00d9ff;
}

.sidebar-menu {
  background: transparent;
  border: none;
}

.sidebar-menu:not(.el-menu--collapse) {
  width: 220px;
}

:deep(.el-menu-item) {
  color: #a0a0a0;
}

:deep(.el-menu-item.is-active) {
  background: #16213e;
  color: #00d9ff;
}

:deep(.el-menu-item:hover) {
  background: #16213e;
  color: #00d9ff;
}

.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 15px;
}

.logout-btn {
  color: #ff6b6b;
}

.logout-btn:hover {
  background: rgba(255, 107, 107, 0.1);
}

.main-content {
  background: #f5f7fa;
  padding: 20px;
  overflow-y: auto;
}

/* AI 对话页：通栏铺满，交给页面自己管理滚动 */
.main-flush {
  padding: 0;
  overflow: hidden;
  background: #fff;
}
</style>