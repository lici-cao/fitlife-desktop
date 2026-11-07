import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue')
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/food',
    name: 'Food',
    component: () => import('../views/Food.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/exercise',
    name: 'Exercise',
    component: () => import('../views/Exercise.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/calculator',
    name: 'Calculator',
    component: () => import('../views/Calculator.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/plan',
    name: 'Plan',
    component: () => import('../views/Plan.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/exercise-library',
    name: 'ExerciseLibrary',
    component: () => import('../views/ExerciseLibrary.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/ai-companion',
    name: 'AICompanion',
    component: () => import('../views/AICompanion.vue'),
    meta: { requiresAuth: true }
  }
]

// 桌面版把前端产物内嵌进后端 static 目录，由 Spring Boot 直接托管静态资源。
// 这种情况下没有服务端 rewrite，history 模式一旦刷新就会 404，因此默认走 hash 模式：
//   http://127.0.0.1:8080/#/dashboard
// 如果你的部署环境已经配好 frontend fallback（例如 Nginx try_files），
// 可以设置 VITE_ROUTER_MODE=history 恢复 history 模式。
const useHashMode = import.meta.env.VITE_ROUTER_MODE !== 'history'

const router = createRouter({
  history: useHashMode ? createWebHashHistory() : createWebHistory(),
  routes
})

export { useHashMode }

// 路由守卫
router.beforeEach((to, _from, next) => {
  const token = localStorage.getItem('fitlife_token')
  const requiresAuth = to.meta.requiresAuth

  if (requiresAuth && !token) {
    next('/login')
  } else if ((to.path === '/login' || to.path === '/register') && token) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router