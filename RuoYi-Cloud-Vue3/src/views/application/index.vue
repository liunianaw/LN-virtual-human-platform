<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header><div class="header"><span>我的 Application</span><el-button type="primary" @click="createOpen = true">创建</el-button></div></template>
      <el-table v-loading="loading" :data="items" border>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="revision" label="修订" width="90" />
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">配置</el-button>
            <el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggle(row)">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createOpen" title="创建 Application" width="460px">
      <el-form label-width="72px">
        <el-form-item label="名称" required><el-input v-model="createForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="createForm.description" type="textarea" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :disabled="!createForm.name.trim()" @click="create">创建</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailOpen" title="Application 当前配置" size="720px">
      <template v-if="detail">
        <el-alert title="保存会立即替换当前配置；新 Session 使用新修订，已有 Session 保持创建时快照。" type="info" :closable="false" class="mb16" />
        <el-form label-width="110px">
          <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="form.description" type="textarea" maxlength="1000" /></el-form-item>
          <el-form-item label="虚拟人" required>
            <el-select v-model="form.avatarId" filterable class="full"><el-option v-for="item in choices.avatars" :key="item.avatarId" :value="item.avatarId" :label="`${item.name} · ${item.visibility}`" /></el-select>
          </el-form-item>
          <el-form-item label="官方声音" required>
            <el-select v-model="form.voiceId" filterable class="full"><el-option v-for="item in choices.voices" :key="item.voiceId" :value="item.voiceId" :label="`${item.name} · ${item.voiceAlias}`" /></el-select>
          </el-form-item>
          <el-form-item label="System Prompt"><el-input v-model="form.systemPrompt" type="textarea" :rows="5" maxlength="32768" show-word-limit /></el-form-item>
          <el-form-item label="Skills">
            <el-select v-model="form.skillIds" multiple filterable clearable class="full">
              <el-option v-for="item in choices.skills" :key="item.skillId" :value="item.skillId" :label="`${item.name} · ${item.skillType}${item.toolName ? ' · ' + item.toolName : ''}`" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" :disabled="!form.name.trim() || !form.avatarId || !form.voiceId" @click="save">保存当前配置</el-button></el-form-item>
        </el-form>

        <el-divider>Application Secret</el-divider>
        <el-alert title="Secret 只在创建或重置响应中显示一次，仅保存在可信开发者后端。" type="warning" :closable="false" />
        <el-button class="secret-action" :disabled="detail.status !== 'ACTIVE'" @click="resetSecret">
          {{ secrets.some((item: ApplicationSecretSummary) => item.status === 'ACTIVE') ? '重置 Secret' : '创建 Secret' }}
        </el-button>
        <el-table :data="secrets" border>
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="displaySuffix" label="末尾" width="100" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="操作" width="130"><template #default="{ row }"><el-button link :disabled="row.status !== 'ACTIVE'" @click="changeSecret(row, 'disable')">停用</el-button><el-button link :disabled="row.status === 'DELETED'" @click="changeSecret(row, 'delete')">删除</el-button></template></el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="secretOpen" title="立即保存 Application Secret" width="580px" @closed="issuedSecret = ''">
      <el-alert title="关闭后无法再次查看；请现在复制到可信后端的秘密配置。" type="warning" :closable="false" />
      <el-input class="secret-action" :model-value="issuedSecret" readonly />
      <template #footer><el-button @click="copySecret">复制</el-button><el-button type="primary" @click="secretOpen = false">已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeApplicationSecret, changeApplicationStatus, createApplication, getApplication,
  listApplicationChoices, listApplicationSecrets, listApplications, resetApplicationSecret,
  updateApplication, type ApplicationChoices, type ApplicationDetail, type ApplicationSecretSummary,
  type ApplicationSummary
} from '@/api/application'

const { proxy } = getCurrentInstance()!
const loading = ref(false), createOpen = ref(false), detailOpen = ref(false), secretOpen = ref(false)
const items = ref<ApplicationSummary[]>([]), detail = ref<ApplicationDetail>()
const secrets = ref<ApplicationSecretSummary[]>([]), issuedSecret = ref('')
const createForm = reactive({ name: '', description: '' })
const choices = reactive<ApplicationChoices>({ avatars: [], voices: [], skills: [] })
const form = reactive({ name: '', description: '', avatarId: '', voiceId: '', systemPrompt: '', skillIds: [] as string[] })

function load() {
  loading.value = true
  listApplications().then(response => { items.value = response.data?.items || [] }).finally(() => { loading.value = false })
}

function create() {
  createApplication({ name: createForm.name.trim(), description: createForm.description.trim() || undefined }).then(response => {
    createOpen.value = false; createForm.name = ''; createForm.description = ''; load()
    if (response.data) openDetail(response.data)
  })
}

function openDetail(row: ApplicationSummary) {
  Promise.all([getApplication(row.applicationId), listApplicationChoices(row.applicationId), listApplicationSecrets(row.applicationId)])
    .then(([application, resources, keys]) => {
      if (!application.data) return
      detail.value = application.data
      Object.assign(choices, resources.data || { avatars: [], voices: [], skills: [] })
      secrets.value = keys.data || []
      form.name = application.data.name; form.description = application.data.description || ''
      form.avatarId = application.data.avatarId || ''; form.voiceId = application.data.voiceId || ''
      form.systemPrompt = application.data.systemPrompt || ''
      form.skillIds = (application.data.skills || []).map(item => item.skillId)
      detailOpen.value = true
    })
}

function save() {
  if (!detail.value) return
  updateApplication(detail.value.applicationId, detail.value.revision, {
    name: form.name.trim(), description: form.description.trim() || undefined,
    avatarId: form.avatarId, voiceId: form.voiceId, systemPrompt: form.systemPrompt.trim() || undefined,
    skills: form.skillIds.map((skillId: string, sortOrder: number) => ({ skillId, sortOrder }))
  }).then(response => {
    proxy?.$modal.msgSuccess('当前配置已保存。')
    if (response.data) openDetail(response.data)
    load()
  })
}

function toggle(row: ApplicationSummary) {
  const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  changeApplicationStatus(row.applicationId, row.revision, status, status === 'ACTIVE' ? '开发者重新启用' : '开发者主动停用')
    .then(() => load())
}

function resetSecret() {
  if (!detail.value) return
  const application = detail.value
  ElMessageBox.prompt('旧 Secret 的新请求会立即失效。请输入新 Secret 名称。', '重置 Application Secret', {
    inputPattern: /\S/, inputErrorMessage: '名称不能为空', inputValue: application.name + ' Secret'
  }).then(({ value }: { value: string }) => resetApplicationSecret(application.applicationId, value.trim()).then(response => {
    openDetail(application)
    if (response.data?.secret) { issuedSecret.value = response.data.secret; secretOpen.value = true }
    else ElMessage.warning('操作已完成，但完整 Secret 已不可再次显示；如未保存请重新重置。')
  }))
}

function changeSecret(secret: ApplicationSecretSummary, action: 'disable' | 'delete') {
  if (!detail.value) return
  changeApplicationSecret(detail.value.applicationId, secret.keyId, action).then(() => openDetail(detail.value!))
}

function copySecret() {
  navigator.clipboard.writeText(issuedSecret.value).then(() => ElMessage.success('已复制'))
}

onMounted(load)
</script>

<style scoped>
.header { display: flex; align-items: center; justify-content: space-between; }
.full { width: 100%; }
.mb16 { margin-bottom: 16px; }
.secret-action { margin: 14px 0; }
</style>
