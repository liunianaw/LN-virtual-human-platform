<template>
  <div class="app-container">
    <el-alert title="应用只绑定已发布的角色版本和官方声音版本。保存配置不会发起合成或收费调用。" type="info" :closable="false" />
    <el-row class="toolbar"><el-button type="primary" @click="openCreate">创建应用</el-button><el-button @click="reload">刷新</el-button></el-row>
    <el-table v-loading="loading" :data="items" @row-click="openDetail">
      <el-table-column prop="name" label="应用" min-width="180" /><el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="configStatus" label="配置" width="130" /><el-table-column prop="currentConfigVersionId" label="当前配置版本" min-width="150" />
      <el-table-column label="操作" width="220"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">配置</el-button><el-button link :disabled="row.configStatus !== 'CONFIGURED' || row.status !== 'ACTIVE'" @click.stop="debug(row)">进入调试</el-button><el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click.stop="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="createOpen" title="创建应用" width="460px"><el-form :model="createForm" label-width="72px"><el-form-item label="名称" required><el-input v-model="createForm.name" maxlength="100" /></el-form-item><el-form-item label="说明"><el-input v-model="createForm.description" type="textarea" maxlength="1000" /></el-form-item></el-form><template #footer><el-button @click="createOpen=false">取消</el-button><el-button type="primary" :disabled="!createForm.name.trim()" @click="create">创建</el-button></template></el-dialog>
    <el-drawer v-model="detailOpen" title="应用配置" size="580px"><template v-if="detail"><p>状态：{{ detail.status }} · 修订：{{ detail.revision }}</p><p v-if="detail.currentConfig">当前版本：{{ detail.currentConfig.versionNo }}（{{ detail.currentConfig.configVersionId }}）</p><el-form label-width="110px"><el-form-item label="角色版本"><el-select v-model="config.avatarVersionId" filterable class="full"><el-option v-for="item in choices.avatars" :key="item.versionId" :value="item.versionId" :label="`${item.name} · v${item.versionNo} · ${item.visibility}`" /></el-select></el-form-item><el-form-item label="官方声音版本"><el-select v-model="config.voiceVersionId" filterable class="full"><el-option v-for="item in choices.voices" :key="item.versionId" :value="item.versionId" :label="`${item.name} · ${item.voiceAlias} · v${item.versionNo}`" /></el-select></el-form-item></el-form><el-empty v-if="!choices.voices.length" description="暂无已发布官方声音，等待管理员发布后再配置应用。" /><el-button type="primary" :disabled="detail.status !== 'ACTIVE' || !config.avatarVersionId || !config.voiceVersionId" @click="publish">发布新配置版本</el-button><el-divider>历史版本</el-divider><el-table :data="detail.versions"><el-table-column prop="versionNo" label="版本" width="90" /><el-table-column prop="avatarVersionId" label="角色版本" /><el-table-column prop="voiceVersionId" label="声音版本" /></el-table></template></el-drawer>
    <el-dialog v-model="debugOpen" title="应用调试" width="720px" @closed="closeDebug"><el-alert title="仅在点击播报后才触发合成；应用配置保存本身不会发起调用。" type="warning" :closable="false" /><RuntimeSpeechPlayer v-if="debugToken" :token="debugToken" class="player" /></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeApplicationStatus, createApplication, createApplicationDebugSession, getApplication, listApplicationChoices, listApplications, publishApplicationConfig, type ApplicationChoices, type ApplicationDetail, type ApplicationSummary } from '@/api/application'
import { closeDebugSession, mintDebugSessionToken } from '@/api/asset/official-voice'
import RuntimeSpeechPlayer from '@/views/voice/official/RuntimeSpeechPlayer.vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const loading = ref(false), createOpen = ref(false), detailOpen = ref(false), debugOpen = ref(false), items = ref<ApplicationSummary[]>([]), detail = ref<ApplicationDetail>()
const debugToken = ref(''), debugSessionId = ref('')
const choices = reactive<ApplicationChoices>({ avatars: [], voices: [] })
const createForm = reactive({ name: '', description: '' }), config = reactive({ avatarVersionId: '', voiceVersionId: '' })
function reload() { loading.value = true; listApplications().then(res => { items.value = res.data?.items || []; const applicationId = typeof route.query.applicationId === 'string' ? route.query.applicationId : ''; const avatarVersionId = typeof route.query.avatarVersionId === 'string' ? route.query.avatarVersionId : ''; const target = items.value.find((item: ApplicationSummary) => item.applicationId === applicationId); if (target) openDetail(target, avatarVersionId) }).finally(() => { loading.value = false }) }
function openCreate() { createForm.name = ''; createForm.description = ''; createOpen.value = true }
function create() { createApplication({ name: createForm.name.trim(), description: createForm.description.trim() || undefined }).then(() => { createOpen.value = false; ElMessage.success('应用已创建，请发布首个配置版本。'); reload() }) }
function openDetail(row: ApplicationSummary, adoptedAvatarVersionId = '') { Promise.all([getApplication(row.applicationId), listApplicationChoices()]).then(([app, resources]) => { detail.value = app.data; Object.assign(choices, resources.data || { avatars: [], voices: [] }); config.avatarVersionId = adoptedAvatarVersionId || app.data?.currentConfig?.avatarVersionId || ''; config.voiceVersionId = app.data?.currentConfig?.voiceVersionId || ''; detailOpen.value = true }) }
function publish() { if (!detail.value) return; publishApplicationConfig(detail.value.applicationId, detail.value.revision, { mode: 'SPEAK_ONLY', avatarVersionId: config.avatarVersionId, voiceVersionId: config.voiceVersionId, contextPolicy: { enabled: false } }).then(() => { ElMessage.success('已发布固定配置版本。'); openDetail(detail.value!) }) }
function toggle(row: ApplicationSummary) { const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; ElMessageBox.prompt(status === 'DISABLED' ? '停用会撤销正在使用的调试会话。请输入原因。' : '请输入重新启用原因。', `${status === 'DISABLED' ? '停用' : '启用'}应用`, { inputPattern: /\S/, inputErrorMessage: '原因不能为空' }).then(({ value }: { value: string }) => changeApplicationStatus(row.applicationId, row.revision, status, value).then(() => { ElMessage.success('应用状态已更新。'); reload() })) }
function debug(row: ApplicationSummary) { createApplicationDebugSession(row.applicationId).then(res => { const sessionId = res.data?.sessionId; if (!sessionId) throw new Error('DEBUG Session 未返回 ID'); debugSessionId.value = sessionId; return mintDebugSessionToken(sessionId) }).then(res => { debugToken.value = res.data?.token || ''; if (!debugToken.value) throw new Error('DEBUG Session 未返回授权'); debugOpen.value = true }).catch(error => { closeDebug(); throw error }) }
function closeDebug() { const sessionId = debugSessionId.value; debugSessionId.value = ''; debugToken.value = ''; if (sessionId) closeDebugSession(sessionId).catch(() => {}) }
reload()
</script>

<style scoped>
.toolbar { margin: 16px 0; gap: 8px; }.full { width: 100%; }.player { margin-top: 16px; }
</style>
