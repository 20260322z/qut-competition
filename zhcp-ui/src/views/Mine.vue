<template>
  <div>
    <h2>我的综测</h2>
    <el-alert v-if="detail.packReady === false" type="warning" :closable="false" :title="detail.message || '本院细则未入库，暂不自动计分'" style="margin-bottom:12px" />
    <el-descriptions :column="3" border>
      <el-descriptions-item v-for="(v, k) in labels" :key="k" :label="v">
        {{ detail.score?.[k] ?? 0 }}
      </el-descriptions-item>
    </el-descriptions>
    <el-upload class="up" :auto-upload="false" :on-change="onFile" accept=".zip,.pdf,.png,.jpg,.jpeg,.docx,.xlsx">
      <el-button type="primary">上传材料（建议 zip）</el-button>
    </el-upload>
    <el-button :loading="loading" type="success" :disabled="!file" @click="send">提交审核</el-button>
    <el-table :data="detail.items || []" style="margin-top:16px" border>
      <el-table-column prop="title" label="项目" />
      <el-table-column prop="applied_points" label="计分" width="80" />
      <el-table-column prop="authenticity" label="真伪" width="140" />
      <el-table-column prop="authenticity_reason" label="说明" />
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { mine, uploadMine } from '../api'
import { ElMessage } from 'element-plus'

const detail = ref({})
const file = ref(null)
const loading = ref(false)
const labels = {
  moral_peer: '班内评价', moral_reward: '思想奖励', moral_deduct: '思想扣分',
  academic_base: '专业基础', total_score: '总分', zhcp_rank: '综测排名'
}

async function load() {
  const res = await mine()
  detail.value = res.data || {}
}

function onFile(f) { file.value = f.raw }

async function send() {
  loading.value = true
  try {
    const res = await uploadMine(file.value)
    detail.value = res.data || {}
    ElMessage.success(res.data?.message || '已提交审核')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.up { margin: 16px 12px 16px 0; display: inline-block; }
</style>
