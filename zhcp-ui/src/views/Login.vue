<template>
  <div class="wrap">
    <div class="card">
      <h1>青理综测</h1>
      <p>团支书使用学工账号，学生使用教务账号。密码仅加密保存，接口不回传明文。</p>
      <el-radio-group v-model="role" class="role">
        <el-radio-button value="secretary">团支书</el-radio-button>
        <el-radio-button value="student">学生</el-radio-button>
      </el-radio-group>
      <el-form @submit.prevent="submit">
        <el-form-item><el-input v-model="username" :placeholder="role === 'secretary' ? '学工账号' : '教务账号'" /></el-form-item>
        <el-form-item><el-input v-model="password" type="password" show-password placeholder="密码" /></el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="submit">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api'

const router = useRouter()
const role = ref('secretary')
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const res = await login(role.value, username.value, password.value)
    localStorage.setItem('zhcp_token', res.token || res.data?.token)
    localStorage.setItem('zhcp_user', JSON.stringify(res.data?.user || res.user || {}))
    router.push(role.value === 'student' ? '/mine' : '/table')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; background: linear-gradient(160deg, #1f4b99, #315ef5); }
.card { width: 380px; background: #fff; border-radius: 16px; padding: 32px; box-shadow: 0 16px 40px rgba(0,0,0,.18); }
h1 { margin: 0 0 8px; color: #18243d; }
p { color: #7b879c; font-size: 13px; line-height: 1.6; }
.role { margin: 16px 0 20px; }
</style>
