<template>
  <div v-if="detail.score">
    <el-page-header @back="$router.push('/table')" :content="(detail.roster?.student_name || '') + ' 综测明细'" />
    <el-form :inline="true" class="form">
      <el-form-item v-for="f in fields" :key="f.k" :label="f.l">
        <el-input-number v-model="form[f.k]" :step="0.1" :precision="2" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="save">保存改分</el-button>
      </el-form-item>
    </el-form>
    <h3>加分项与真伪</h3>
    <el-table :data="detail.items || []" border>
      <el-table-column prop="title" label="项目" />
      <el-table-column prop="clause" label="条款" width="140" />
      <el-table-column prop="raw_points" label="建议分" width="80" />
      <el-table-column prop="applied_points" label="已计分" width="80" />
      <el-table-column label="真伪" width="120">
        <template #default="{ row }">
          <el-tag :type="row.authenticity === 'likely_genuine' ? 'success' : row.authenticity === 'suspicious' ? 'warning' : 'danger'">
            {{ row.authenticity }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="authenticity_reason" label="理由" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button v-if="row.suggested && !row.confirmed" text type="primary" @click="ok(row, true)">确认生效</el-button>
          <el-button v-if="!row.rejected" text type="danger" @click="ok(row, false)">不采纳</el-button>
        </template>
      </el-table-column>
    </el-table>
    <h3>材料原图</h3>
    <div class="files">
      <el-card v-for="f in detail.files || []" :key="f.file_id" class="file">
        <div>{{ f.original_name }} · {{ f.authenticity }}</div>
        <div class="reason">{{ f.authenticity_reason }}</div>
        <el-link :href="fileUrl(f.file_id)" target="_blank">打开原图</el-link>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { confirmItem, scoreDetail, updateScore, fileUrl } from '../api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const detail = ref({})
const fields = [
  { k: 'moral_peer', l: '班内评价' }, { k: 'moral_reward', l: '思想奖励' }, { k: 'moral_deduct', l: '思想扣分' },
  { k: 'academic_base', l: '专业基础' }, { k: 'academic_reward', l: '学业奖励' }, { k: 'academic_deduct', l: '学业扣分' },
  { k: 'body_pe', l: '体育' }, { k: 'body_mental', l: '心理' }, { k: 'body_labor', l: '劳动' },
  { k: 'body_reward', l: '身心奖励' }, { k: 'body_deduct', l: '身心扣分' },
  { k: 'develop_work', l: '社会工作' }, { k: 'develop_talent', l: '特长' }, { k: 'develop_tech', l: '科技' }
]
const form = reactive({})

async function load() {
  const res = await scoreDetail(route.params.rosterId)
  detail.value = res.data || {}
  const s = detail.value.score || {}
  fields.forEach((f) => { form[f.k] = Number(s[f.k] || 0) })
}

async function save() {
  await updateScore(route.params.rosterId, form)
  ElMessage.success('已保存，写入审计日志')
  load()
}

async function ok(row, pass) {
  await confirmItem(row.item_id, pass)
  load()
}

onMounted(load)
</script>

<style scoped>
.form { margin: 16px 0; background: #fff; padding: 16px; border-radius: 8px; }
.files { display: flex; flex-wrap: wrap; gap: 12px; }
.file { width: 280px; }
.reason { color: #909399; font-size: 12px; margin: 8px 0; }
</style>
