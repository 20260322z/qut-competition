<template>
  <div>
    <div class="bar">
      <h2>{{ data.college }} {{ data.className }} · {{ data.semester }}</h2>
      <el-alert v-if="data.packReady === false" type="warning" :closable="false" title="本院细则未确认，学生上传不会自动计分。请到「规则包」上传并确认。" style="margin-right:12px" />
      <el-button type="primary" @click="load">刷新</el-button>
      <el-button @click="download">导出加分库</el-button>
    </div>
    <el-table :data="data.rows || []" border stripe height="calc(100vh - 180px)">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="student_name" label="姓名" width="90" />
      <el-table-column prop="student_no" label="学号" width="130" />
      <el-table-column label="注册" width="80">
        <template #default="{ row }">
          <el-tag :type="row.registered ? 'success' : 'info'">{{ row.registered ? '已注册' : '未注册' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="moral_peer" label="班内评价" width="90" />
      <el-table-column prop="moral_reward" label="思想奖励" width="90" />
      <el-table-column prop="moral_deduct" label="思想扣分" width="90" />
      <el-table-column prop="academic_base" label="专业基础" width="90" />
      <el-table-column prop="academic_reward" label="学业奖励" width="90" />
      <el-table-column prop="academic_deduct" label="学业扣分" width="90" />
      <el-table-column prop="body_pe" label="体育" width="70" />
      <el-table-column prop="body_mental" label="心理" width="70" />
      <el-table-column prop="body_labor" label="劳动" width="70" />
      <el-table-column prop="body_reward" label="身心奖励" width="90" />
      <el-table-column prop="body_deduct" label="身心扣分" width="90" />
      <el-table-column prop="develop_work" label="社会工作" width="90" />
      <el-table-column prop="develop_talent" label="特长" width="70" />
      <el-table-column prop="develop_tech" label="科技" width="70" />
      <el-table-column prop="total_score" label="总分" width="80" />
      <el-table-column prop="zhcp_rank" label="综测排名" width="90" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="$router.push(`/review/${row.roster_id}`)">明细/改分</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { classTable, exportUrl } from '../api'

const data = ref({})

async function load() {
  const res = await classTable()
  data.value = res.data || {}
}

function download() {
  const token = localStorage.getItem('zhcp_token')
  fetch(exportUrl, { headers: { Authorization: `Bearer ${token}` } })
    .then((r) => r.blob())
    .then((blob) => {
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = '综测加分库.xlsx'
      a.click()
    })
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
h2 { margin: 0; flex: 1; font-size: 18px; }
</style>
