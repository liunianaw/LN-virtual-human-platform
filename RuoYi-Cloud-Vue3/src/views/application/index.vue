<template>
  <div class="application-page">
    <section v-show="!detailOpen" ref="listPane" class="application-list">
      <header class="console-page-heading"><div><h1>我的应用</h1><p>为你的应用选择形象、声音与行为。</p></div><el-button type="primary" @click="createOpen = true">创建应用</el-button></header>
      <div class="application-tools"><el-input v-model="keyword" clearable maxlength="100" placeholder="搜索应用名称" aria-label="搜索应用名称" @keyup.enter="search" @clear="search" /><el-button @click="search">查询</el-button></div>
      <el-alert v-if="loadFailed" title="应用列表加载失败，请刷新重试。" type="error" :closable="false" />
      <el-table v-loading="loading || detailLoading" :data="items" empty-text="暂无应用，点击创建应用开始配置">
        <el-table-column label="应用" min-width="210"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ row.name }}</el-button><p class="application-description">{{ row.description || '未添加说明' }}</p></template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><span class="status-text" :class="{ active: row.status === 'ACTIVE' }">{{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}</span></template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">配置</el-button><el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'primary'" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page="pageNum" :limit="pageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="changePage" />
    </section>

    <section v-if="detailOpen && detail" class="application-layout" v-loading="detailLoading">
      <Teleport v-if="pageActive" to="#console-page-path">
        <nav class="application-path" aria-label="应用路径"><button @click="closeDetail">我的应用</button><span aria-hidden="true">›</span><span class="path-current" aria-current="page">{{ detail.name }}</span></nav>
      </Teleport>
      <aside class="application-portrait">
        <span class="portrait-label">当前应用形象</span>
        <div class="portrait-image" v-loading="portraitLoading"><img v-if="portraitUrl && !portraitFailed" :src="portraitUrl" :alt="selectedAvatar?.name || '当前角色'" @error="portraitFailed = true" /><p v-else class="resource-fallback">{{ portraitLoading ? '正在加载形象' : portraitFailed ? '形象加载失败，请重新选择或查看角色详情' : form.avatarId ? '该角色暂无可用形象预览' : '选择一个虚拟角色' }}</p></div>
        <div class="portrait-caption"><h2>{{ selectedAvatar?.name || '尚未选择角色' }}</h2><p>{{ selectedAvatar?.visibility === 'OFFICIAL' ? '官方角色' : '个人角色' }} · {{ portraitFailed ? '预览不可用' : '形象预览' }}</p><el-button v-if="form.avatarId" link type="primary" @click="roleOpen = true">查看角色详情 ↗</el-button></div>
      </aside>
      <div class="application-configuration">
        <el-form ref="configForm" :model="form" :rules="rules" label-position="top" class="application-form">
          <section class="configuration-section"><h3>基本信息</h3><div class="configuration-fields">
            <el-form-item label="应用名称" prop="name"><el-input v-model="form.name" maxlength="100" /></el-form-item>
            <el-form-item label="应用说明"><el-input v-model="form.description" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" maxlength="1000" /></el-form-item>
          </div></section>
          <section class="configuration-section"><h3>角色与声音</h3><div class="configuration-fields">
            <el-form-item label="虚拟角色" prop="avatarId"><el-select v-model="form.avatarId" filterable placeholder="选择已发布角色" class="full"><el-option v-for="item in choices.avatars" :key="item.avatarId" :value="item.avatarId" :label="item.name + (item.visibility === 'OFFICIAL' ? ' · 官方角色' : ' · 个人角色')" /></el-select><small>使用该角色的形象与动作。</small><router-link v-if="!choices.avatars.length" to="/system/my-avatars">查看我的角色</router-link></el-form-item>
            <el-form-item label="官方声音" prop="voiceId"><el-select v-model="form.voiceId" filterable placeholder="选择已发布声音" class="full"><el-option v-for="item in choices.voices" :key="item.voiceId" :value="item.voiceId" :label="item.name + ' · ' + item.voiceAlias" /></el-select><small>用于应用的语音播报。声音与形象独立选择。</small><span v-if="!choices.voices.length" class="field-message">暂无可用官方声音，请联系管理员发布声音资源。</span></el-form-item>
          </div></section>
          <section class="configuration-section"><h3>行为配置</h3>
            <el-form-item label="System Prompt"><el-input v-model="form.systemPrompt" type="textarea" :rows="4" maxlength="32768" /></el-form-item>
            <el-form-item label="Skills"><el-select v-model="form.skillIds" multiple filterable clearable placeholder="选择需要的 Skills" class="full"><el-option v-for="item in choices.skills" :key="item.skillId" :value="item.skillId" :label="item.name + ' · ' + item.skillType + (item.toolName ? ' · ' + item.toolName : '')" /></el-select><router-link v-if="!choices.skills.length" to="/system/skills">创建私有 Skill</router-link></el-form-item>
          </section>
          <details class="application-credentials"><summary>接入凭证</summary><p>Secret 只在创建或重置时显示一次，仅保存在可信开发者后端。</p>
            <el-button :disabled="detail.status !== 'ACTIVE'" @click="resetSecret">{{ secrets.some((item: ApplicationSecretSummary) => item.status === 'ACTIVE') ? '重置 Secret' : '创建 Secret' }}</el-button>
            <el-table :data="secrets" empty-text="尚未创建接入凭证"><el-table-column prop="name" label="名称" /><el-table-column prop="displaySuffix" label="末尾" width="90" /><el-table-column prop="status" label="状态" width="100" /><el-table-column label="操作" width="130"><template #default="{ row }"><el-button link :disabled="row.status !== 'ACTIVE'" @click="changeSecret(row, 'disable')">停用</el-button><el-button link :disabled="row.status === 'DELETED'" @click="changeSecret(row, 'delete')">删除</el-button></template></el-table-column></el-table>
          </details>
        </el-form>
        <footer class="configuration-save"><div><span class="status-text" :class="{ active: detail.status === 'ACTIVE' }">{{ detail.status === 'ACTIVE' ? '已启用' : '已停用' }}</span><p aria-live="polite">{{ dirty ? '有未保存的修改' : '当前配置已保存' }}。新 Session 使用新配置，已有 Session 保持创建时快照。</p></div><el-button type="primary" :loading="saving" @click="save">保存配置</el-button></footer>
      </div>
    </section>
    <div class="console-bottom-bar"><strong>{{ detailOpen ? '应用配置' : '我的应用' }}</strong><span>为你的应用选择形象、声音与行为。</span></div>

    <el-dialog v-model="createOpen" title="创建应用" width="460px"><el-form label-position="top"><el-form-item label="名称" required><el-input v-model="createForm.name" maxlength="100" /></el-form-item><el-form-item label="说明"><el-input v-model="createForm.description" type="textarea" maxlength="1000" /></el-form-item></el-form><template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :disabled="!createForm.name.trim()" @click="create">创建</el-button></template></el-dialog>
    <el-dialog v-model="roleOpen" :title="selectedAvatar?.name || '角色详情'" width="560px"><img v-if="portraitUrl && !portraitFailed" class="role-detail-image" :src="portraitUrl" :alt="selectedAvatar?.name || '当前角色'" /><p v-else class="resource-fallback">暂无可用形象预览</p><p>角色：{{ selectedAvatar?.name || form.avatarId }}</p><p>资源状态：{{ portraitDetail?.status || '以资源目录为准' }}</p><router-link v-if="selectedAvatar?.visibility === 'PRIVATE'" to="/system/my-avatars">打开我的角色，查看版本与动作</router-link><p v-else>当前为可选择的官方角色；动作资源由管理员维护。</p></el-dialog>
    <el-dialog v-model="secretOpen" title="立即保存 Application Secret" width="580px" @closed="issuedSecret = ''"><el-alert title="关闭后无法再次查看；请现在复制到可信后端的秘密配置。" type="warning" :closable="false" /><el-input class="secret-action" :model-value="issuedSecret" readonly /><template #footer><el-button @click="copySecret">复制</el-button><el-button type="primary" @click="secretOpen = false">已保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts" name="Applications">
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import { getAvatarDetail, getAvatarVersionPreview, type AvatarDetail } from '@/api/asset/avatar'
import {
  changeApplicationSecret, changeApplicationStatus, createApplication, getApplication,
  listApplicationChoices, listApplicationSecrets, listApplications, resetApplicationSecret,
  updateApplication, type ApplicationChoices, type ApplicationDetail, type ApplicationSecretSummary,
  type ApplicationSummary
} from '@/api/application'
const { proxy } = getCurrentInstance()!
const route = useRoute()
const pageActive = ref(true)
onActivated(() => { pageActive.value = true })
onDeactivated(() => { pageActive.value = false })
const pageNum = ref(1), pageSize = ref(20), total = ref(0)
const loading = ref(false), loadFailed = ref(false), createOpen = ref(false), detailOpen = ref(false), secretOpen = ref(false)
const detailLoading = ref(false), saving = ref(false), keyword = ref(''), roleOpen = ref(false)
const items = ref<ApplicationSummary[]>([]), detail = ref<ApplicationDetail>()
const secrets = ref<ApplicationSecretSummary[]>([]), issuedSecret = ref('')
const createForm = reactive({ name: '', description: '' })
const choices = reactive<ApplicationChoices>({ avatars: [], voices: [], skills: [] })
const form = reactive({ name: '', description: '', avatarId: '', voiceId: '', systemPrompt: '', skillIds: [] as string[] })
const configForm = ref<{ validate: () => Promise<boolean> }>()
const rules = { name: [{ validator: (_: unknown, value: string, callback: (error?: Error) => void) => callback(value.trim() ? undefined : new Error('请输入应用名称')), trigger: 'blur' }], avatarId: [{ required: true, message: '请选择虚拟角色', trigger: 'change' }], voiceId: [{ required: true, message: '请选择官方声音', trigger: 'change' }] }
const baseline = ref('')
const dirty = computed(() => detailOpen.value && baseline.value !== JSON.stringify(form))
const selectedAvatar = computed(() => choices.avatars.find((item: ApplicationChoices['avatars'][number]) => item.avatarId === form.avatarId))
const portraitUrl = ref(''), portraitFailed = ref(false), portraitLoading = ref(false), portraitDetail = ref<AvatarDetail>()
let portraitRequest = 0
watch(() => form.avatarId, async (id: string) => {
  const request = ++portraitRequest
  portraitUrl.value = ''; portraitFailed.value = false; portraitDetail.value = undefined
  if (!id) { portraitLoading.value = false; return }
  portraitLoading.value = true
  try {
    const result = await getAvatarDetail(id)
    if (request !== portraitRequest) return
    portraitDetail.value = result.data
    portraitUrl.value = result.data?.previewUrl || ''
    const version = result.data?.currentVersionId
    if (!portraitUrl.value && version) {
      const preview = await getAvatarVersionPreview(id, version)
      if (request === portraitRequest) portraitUrl.value = preview.data?.baseImageUrl || preview.data?.previewUrl || ''
    }
  } catch { if (request === portraitRequest) portraitFailed.value = true }
  finally { if (request === portraitRequest) portraitLoading.value = false }
})
async function confirmLeave() {
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('当前配置尚未保存。离开将放弃这些修改。', '离开应用配置', { confirmButtonText: '放弃修改并离开', cancelButtonText: '继续编辑', type: 'warning' }); return true } catch { return false }
}
async function closeDetail() { if (await confirmLeave()) { detailOpen.value = false; baseline.value = ''; } }
onBeforeRouteLeave(confirmLeave)
let listRequest = 0
function load() {
  const request = ++listRequest
  loading.value = true; loadFailed.value = false
  return listApplications({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value.trim() || undefined }).then(response => { if (request !== listRequest) return; items.value = response.data?.items || []; total.value = response.data?.total || 0 }).catch(() => { if (request === listRequest) loadFailed.value = true }).finally(() => { if (request === listRequest) loading.value = false })
}
function changePage({ page, limit }: { page: number; limit: number }) { pageNum.value = pageSize.value === limit ? page : 1; pageSize.value = limit; load() }
function search() { pageNum.value = 1; load() }
function create() {
  createApplication({ name: createForm.name.trim(), description: createForm.description.trim() || undefined }).then(response => {
    createOpen.value = false; createForm.name = ''; createForm.description = ''; load()
    if (response.data) openDetail(response.data)
  })
}
async function openDetail(row: Pick<ApplicationSummary, 'applicationId'>) {
  if (detailLoading.value) return
  if (detailOpen.value && !(await confirmLeave())) return
  detailLoading.value = true
  try {
    const [application, resources, keys] = await Promise.all([getApplication(row.applicationId), listApplicationChoices(row.applicationId), listApplicationSecrets(row.applicationId)])
    if (!application.data) return
    detail.value = application.data
    Object.assign(choices, resources.data || { avatars: [], voices: [], skills: [] })
    secrets.value = keys.data || []
    form.name = application.data.name; form.description = application.data.description || ''
    form.avatarId = application.data.avatarId || ''; form.voiceId = application.data.voiceId || ''
    form.systemPrompt = application.data.systemPrompt || ''
    form.skillIds = (application.data.skills || []).map(item => item.skillId)
    baseline.value = JSON.stringify(form)
    detailOpen.value = true
  } finally { detailLoading.value = false }
}
async function save() {
  if (!detail.value || saving.value || !(await configForm.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    const response = await updateApplication(detail.value.applicationId, detail.value.revision, {
      name: form.name.trim(), description: form.description.trim() || undefined,
      avatarId: form.avatarId, voiceId: form.voiceId, systemPrompt: form.systemPrompt.trim() || undefined,
      skills: form.skillIds.map((skillId: string, sortOrder: number) => ({ skillId, sortOrder }))
    })
    if (response.data) detail.value = response.data
    baseline.value = JSON.stringify(form)
    proxy?.$modal.msgSuccess('当前配置已保存。')
    load()
  } finally { saving.value = false }
}
async function toggle(row: ApplicationSummary) {
  const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try { await ElMessageBox.confirm(status === 'DISABLED' ? '停用后将拒绝新的应用请求。确认停用？' : '确认重新启用此应用？', '应用状态', { type: 'warning' }); await changeApplicationStatus(row.applicationId, row.revision, status, status === 'ACTIVE' ? '开发者重新启用' : '开发者主动停用'); load() } catch { /* request errors are shown by the shared interceptor */ }
}
function refreshSecrets() { if (detail.value) listApplicationSecrets(detail.value.applicationId).then(response => { secrets.value = response.data || [] }) }
function resetSecret() {
  if (!detail.value) return
  const application = detail.value
  ElMessageBox.prompt('旧 Secret 的新请求会立即失效。请输入新 Secret 名称。', '重置 Application Secret', {
    inputPattern: /\S/, inputErrorMessage: '名称不能为空', inputValue: application.name + ' Secret'
  }).then(({ value }: { value: string }) => resetApplicationSecret(application.applicationId, value.trim()).then(response => {
    refreshSecrets()
    if (response.data?.secret) { issuedSecret.value = response.data.secret; secretOpen.value = true }
    else ElMessage.warning('操作已完成，但完整 Secret 已不可再次显示；如未保存请重新重置。')
  })).catch(() => {})
}
function changeSecret(secret: ApplicationSecretSummary, action: 'disable' | 'delete') {
  if (!detail.value) return
  ElMessageBox.confirm(action === 'delete' ? '删除此凭证后无法恢复。确认继续？' : '停用后此凭证将不能发起新的请求。确认继续？', '凭证管理', { type: 'warning' })
    .then(() => changeApplicationSecret(detail.value!.applicationId, secret.keyId, action)).then(refreshSecrets).catch(() => {})
}
function copySecret() { navigator.clipboard.writeText(issuedSecret.value).then(() => ElMessage.success('已复制')).catch(() => ElMessage.warning('复制失败，请手动选择并保存。')) }
onMounted(async () => {
  await load()
  const id = String(route.query.applicationId || '')
  if (id) await openDetail({ applicationId: id })
})
</script>

<style>
#app .app-wrapper .app-main > .application-page { padding: 0; overflow: visible; background: transparent; border: 0; box-shadow: none; display: flex; flex-direction: column; }
.application-list, .application-layout { flex: 1; min-height: 0; background: var(--ln-surface); border: 1px solid var(--ln-line); border-radius: 16px; box-shadow: 0 5px 24px rgba(29,38,56,.035); overflow: hidden; }
.application-list { padding: 32px; overflow: auto; }
.application-tools { display: flex; gap: 16px; margin-bottom: 18px; }
.application-tools .el-input { max-width: 300px; }
.application-description { margin: 4px 0 0; font-size: 14px; color: var(--ln-muted); }
.application-layout { display: grid; grid-template-columns: 3fr 7fr; }
.application-portrait { display: flex; flex-direction: column; min-width: 0; min-height: 0; padding: 28px 24px 32px; margin: 0; background: transparent; font: inherit; color: var(--ln-text); }
.portrait-label { font-size: 14px; color: var(--ln-muted); }
.portrait-image { flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center; padding: 18px 0; }
.portrait-image img { width: 100%; height: 100%; max-height: 490px; object-fit: contain; }
.portrait-caption { flex: none; text-align: center; }
.portrait-caption h2 { margin: 0 0 6px; font-size: 19px; font-weight: 600; }
.portrait-caption p { font-size: 14px; color: var(--ln-muted); margin: 0 0 12px; }
.application-configuration { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.application-form { flex: 1; min-height: 0; overflow: auto; padding: 28px 36px 10px 28px; scrollbar-width: thin; }
.configuration-section + .configuration-section { margin-top: 26px; }
.configuration-section h3 { margin: 0 0 18px; font-size: 16px; font-weight: 600; }
.configuration-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.application-form .el-form-item { margin-bottom: 18px; }
.application-form .el-form-item__label { font-size: 14px; font-weight: 400; color: var(--ln-muted); padding: 0; margin-bottom: 6px; }
.application-form .el-input__wrapper, .application-form .el-select__wrapper, .application-form .el-textarea__inner { background: transparent; border: 0; border-radius: 0; box-shadow: 0 1px 0 var(--ln-underline) !important; padding: 10px 0; font-size: 15px; }
.application-form .el-input__wrapper, .application-form .el-select__wrapper { min-height: 43px; }
.application-form .el-input__wrapper.is-focus, .application-form .el-select__wrapper.is-focused, .application-form .el-textarea__inner:focus { box-shadow: 0 2px 0 var(--ln-accent) !important; }
.application-form .is-error .el-input__wrapper, .application-form .is-error .el-select__wrapper { box-shadow: 0 1px 0 var(--el-color-danger) !important; }
.application-form .el-textarea__inner { resize: vertical; line-height: 1.7; color: var(--ln-text); }
.application-form .el-input__inner { font-size: 15px; color: var(--ln-text); }
.application-form small { display: block; font-size: 14px; line-height: 1.7; color: var(--ln-muted); padding-top: 7px; width: 100%; }
.application-form a { color: var(--ln-accent); font-size: 14px; }
.application-form .field-message { color: var(--el-color-danger); font-size: 14px; line-height: 1.7; }
.application-form .full { width: 100%; }
.application-credentials { padding-top: 16px; border-top: 1px solid var(--ln-line); }
.application-credentials summary { cursor: pointer; font-size: 15px; color: var(--ln-text); padding: 2px 0; }
.application-credentials p { font-size: 14px; color: var(--ln-muted); line-height: 1.7; }
.configuration-save { padding: 18px 36px 24px 28px; display: flex; align-items: center; justify-content: space-between; gap: 18px; }
.configuration-save > div { display: flex; gap: 16px; align-items: center; min-width: 0; }
.configuration-save p { font-size: 14px; color: var(--ln-muted); line-height: 1.6; margin: 0; max-width: 440px; }
.configuration-save .el-button { height: 40px; padding-inline: 24px; flex: none; }
.application-path { display: flex; align-items: center; gap: 12px; font-size: 15px; min-width: 0; }
.application-path button { font: inherit; border: 0; background: transparent; padding: 6px 0; color: var(--ln-muted); white-space: nowrap; cursor: pointer; }
.application-path button:hover { color: var(--ln-accent); }
.application-path .path-current { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ln-text); }
.breadcrumb-container:has(.application-path) .app-breadcrumb { display: none !important; }
.role-detail-image { display: block; margin: auto; width: 100%; height: 320px; object-fit: contain; }
.secret-action { margin: 14px 0; }
@media (max-width: 1200px) { .application-form { padding: 24px 25px 10px 18px; } .configuration-save { padding: 18px 25px 22px 18px; } .application-portrait { padding-inline: 14px; } }
@media (max-width: 950px) { .configuration-fields { grid-template-columns: 1fr; gap: 0; } .configuration-save > div { display: block; } .configuration-save p { margin-top: 6px; } }
@media (max-width: 767px) { .application-layout { display: block; overflow: auto; } .application-portrait { height: 300px; padding: 20px; } .portrait-image { padding: 4px 0 12px; } .portrait-label { display: none; } .portrait-caption h2 { font-size: 17px; } .application-configuration { display: block; } .application-form { overflow: visible; padding: 20px 22px 8px; } .configuration-save { padding: 18px 22px 24px; } .configuration-save > div { max-width: 180px; } .application-list { padding: 22px 16px; } .application-path { gap: 7px; } }
</style>
