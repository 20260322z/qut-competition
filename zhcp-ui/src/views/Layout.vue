<template>
  <el-container class="layout">
    <el-aside width="220px">
      <div class="brand">青理综测</div>
      <el-menu :default-active="$route.path" router background-color="#1f2d4a" text-color="#cfd6e4" active-text-color="#fff">
        <el-menu-item v-if="role !== 'student'" index="/table">本班综测表</el-menu-item>
        <el-menu-item v-if="role !== 'student'" index="/rules">规则包</el-menu-item>
        <el-menu-item v-if="role === 'student'" index="/mine">我的成绩</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header>
        <span>{{ user.college }} {{ user.className }} · {{ user.nickName }}</span>
        <el-button text @click="out">退出</el-button>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const user = computed(() => JSON.parse(localStorage.getItem('zhcp_user') || '{}'))
const role = computed(() => user.value.role)

function out() {
  localStorage.removeItem('zhcp_token')
  localStorage.removeItem('zhcp_user')
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100%; }
.el-aside { background: #1f2d4a; color: #fff; }
.brand { height: 60px; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.el-header { background: #fff; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 1px 0 #eef1f6; }
</style>
