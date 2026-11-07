import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

/**
 * 应用全局状态管理
 * 管理当前选择的日期、登录状态等
 */
export const useAppStore = defineStore('app', () => {
  // 当前选择的日期
  const selectedDate = ref(new Date().toISOString().split('T')[0])
  
  // 登录状态
  const isLoggedIn = ref(false)
  
  // 用户ID
  const userId = ref<string | null>(null)
  
  // 监听日期变化，保存到 localStorage
  watch(selectedDate, (newDate) => {
    localStorage.setItem('fitlife_selectedDate', newDate)
  })
  
  // 监听登录状态变化
  watch(isLoggedIn, (status) => {
    localStorage.setItem('fitlife_isLoggedIn', status.toString())
  })
  
  // 初始化 - 从 localStorage 恢复状态
  const initFromStorage = () => {
    const savedDate = localStorage.getItem('fitlife_selectedDate')
    if (savedDate) {
      selectedDate.value = savedDate
    }
    
    const savedLoginStatus = localStorage.getItem('fitlife_isLoggedIn')
    if (savedLoginStatus === 'true') {
      isLoggedIn.value = true
    }
    
    const savedUserId = localStorage.getItem('fitlife_userId')
    if (savedUserId) {
      userId.value = savedUserId
    }
  }
  
  // 设置日期
  const setDate = (date: string) => {
    selectedDate.value = date
  }
  
  // 设置登录状态
  const setLoginStatus = (status: boolean, uid?: string) => {
    isLoggedIn.value = status
    if (uid) {
      userId.value = uid
      localStorage.setItem('fitlife_userId', uid)
    }
  }
  
  // 清除登录状态
  const clearLoginStatus = () => {
    isLoggedIn.value = false
    userId.value = null
    localStorage.removeItem('fitlife_userId')
    localStorage.removeItem('fitlife_token')
    localStorage.removeItem('fitlife_isLoggedIn')
  }
  
  return {
    selectedDate,
    isLoggedIn,
    userId,
    initFromStorage,
    setDate,
    setLoginStatus,
    clearLoginStatus
  }
})