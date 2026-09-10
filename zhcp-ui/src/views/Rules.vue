<template>
  <div>
    <div class="bar">
      <h2>本院规则包</h2>
      <el-upload :auto-upload="false" :on-change="onFile" accept=".pdf">
        <el-button type="primary">选择细则 PDF</el-button>
      </el-upload>
      <el-button type="success" :loading="uploading" :disabled="!file" @click="upload">上传并抽取草案</el-button>
    </div>
    <el-alert v-if="data.ready" type="success" :closable="false" title="本院已有确认启用的规则包，学生上传将按该包抽证算分。" />
    <el-alert v-else type="warning" :closable="false" title="本院还没有确认启用的规则包。学生可以交材料，但不会自动计分。" />
    <el-table :data="data.packs || []" border style="margin-top:16px">
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="pack_code" label="编码" width="180" />
      <el-table-column label="状态" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.enabled && row.confirmed" type="success">已启用</el-tag>
          <el-tag v-else-if="row.confirmed" type="info">已确认未启用</el-tag>
          <el-tag v-else type="warning">草案待确认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="source_name" label="来源文件" width="180" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button text type="primary" @click="open(row.pack_id)">查看/编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-drawer v-model="show" size="60%" :title="current.title || '规则包'">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="current.title" />
        </el-form-item>
        <el-form-item label="JSON">
          <el-input v-model="current.content_json" type="textarea" :rows="22" />
        </el-form-item>
        <el-form-item>
          <el-button @click="save">保存修改</el-button>
          <el-button type="primary" :disabled="current.confirmed === 1 && current.enabled === 1" @click="confirm">确认启用</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { confirmRule, ruleDetail, ruleList, updateRule, uploadRule } from '../api'
import { ElMessage } from 'element-plus'

const data = ref({})
const file = ref(null)
const uploading = ref(false)
const show = ref(false)
const current = ref({})

async function load() {
  const res = await ruleList()
  data.value = res.data || {}
}

function onFile(f) { file.value = f.raw }

async function upload() {
  uploading.value = true
  try {
    const res = await uploadRule(file.value)
    ElMessage.success(`已抽取 ${res.data?.pages || 0} 页，请核对 JSON 后再确认`)
    await load()
    if (res.data?.packId) open(res.data.packId)
  } finally {
    uploading.value = false
  }
}

async function open(id) {
  const res = await ruleDetail(id)
  current.value = res.data || {}
  show.value = true
}

async function save() {
  await updateRule(current.value.pack_id, { title: current.value.title, contentJson: current.value.content_json })
  ElMessage.success('已保存')
  load()
}

async function confirm() {
  await confirmRule(current.value.pack_id)
  ElMessage.success('已确认启用，本院学生将按此包计分')
  show.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.el-alert { margin-bottom: 12px; }
</style>
