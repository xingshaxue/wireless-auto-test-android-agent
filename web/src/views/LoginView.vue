<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import client, { errorDetail } from '../api/client'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const token = ref('')
const loading = ref(false)

async function login() {
  if (!token.value.trim()) {
    ElMessage.warning('请输入 API Token')
    return
  }
  loading.value = true
  try {
    // 用 GET /api/agents 验证 token 有效性
    await client.get('/agents', {
      headers: { Authorization: `Bearer ${token.value.trim()}` },
    })
    auth.setToken(token.value.trim())
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (e) {
    ElMessage.error(`Token 验证失败：${errorDetail(e)}`)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">无线自动化测试控制台</h2>
      <el-form @submit.prevent="login">
        <el-form-item label="API Token">
          <el-input
            v-model="token"
            type="password"
            show-password
            placeholder="请输入 API Token"
            @keyup.enter="login"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="login-btn" @click="login">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-lighter);
}
.login-card {
  width: 380px;
}
.login-title {
  text-align: center;
  margin: 0 0 24px;
  font-size: 18px;
}
.login-btn {
  width: 100%;
}
</style>
