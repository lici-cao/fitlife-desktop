<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 30, message: '密码长度在 6 到 30 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string) => {
        if (value !== form.password) {
          return Promise.reject(new Error('两次输入的密码不一致'))
        }
        return Promise.resolve()
      },
      trigger: 'blur'
    }
  ]
}

const handleRegister = async () => {
  if (form.password !== form.confirmPassword) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  
  loading.value = true
  try {
    const response = await api.auth.register({
      username: form.username,
      password: form.password
    })
    
    if (response.data.success) {
      ElMessage.success('注册成功，请登录')
      router.push('/login')
    } else {
      ElMessage.error(response.data.message || '注册失败')
    }
  } catch (error: any) {
    console.error('注册失败:', error)
    ElMessage.error(error.response?.data?.message || '注册失败，请检查网络连接')
  } finally {
    loading.value = false
  }
}

const goToLogin = () => {
  router.push('/login')
}
</script>

<template>
  <div class="register-container">
    <div class="register-box">
      <div class="logo-section">
        <el-icon size="48" color="#00d9ff"><Coin /></el-icon>
        <h1>FitLife</h1>
        <p>创建您的健康账户</p>
      </div>
      
      <el-form :model="form" :rules="rules" ref="formRef" class="register-form">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>
        
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            prefix-icon="Lock"
            size="large"
          />
        </el-form-item>
        
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认密码"
            prefix-icon="Lock"
            size="large"
          />
        </el-form-item>
        
        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="register-btn"
            :loading="loading"
            @click="handleRegister"
          >
            注册
          </el-button>
        </el-form-item>
        
        <div class="login-link">
          <span>已有账号？</span>
          <a @click="goToLogin">立即登录</a>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.register-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
}

.register-box {
  width: 400px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.logo-section {
  text-align: center;
  margin-bottom: 30px;
}

.logo-section h1 {
  color: #00d9ff;
  font-size: 32px;
  margin: 10px 0 5px 0;
}

.logo-section p {
  color: #a0a0a0;
  font-size: 14px;
}

.register-form {
  width: 100%;
}

.register-btn {
  width: 100%;
  background: linear-gradient(135deg, #00d9ff, #0099cc);
  border: none;
  height: 48px;
  font-size: 16px;
}

.register-btn:hover {
  background: linear-gradient(135deg, #00c4e6, #0088b3);
}

.login-link {
  text-align: center;
  margin-top: 20px;
  color: #a0a0a0;
  font-size: 14px;
}

.login-link a {
  color: #00d9ff;
  margin-left: 5px;
  cursor: pointer;
}

.login-link a:hover {
  text-decoration: underline;
}

:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
}

:deep(.el-input__wrapper:hover) {
  border-color: #00d9ff;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px rgba(0, 217, 255, 0.2);
  border-color: #00d9ff;
}

:deep(.el-input__prefix) {
  color: #00d9ff;
}
</style>