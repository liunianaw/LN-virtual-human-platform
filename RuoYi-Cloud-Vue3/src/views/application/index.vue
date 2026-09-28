<template>
  <div class="app-container">
    <el-alert title="应用只绑定已发布的角色版本和官方声音版本。保存配置不会发起合成或收费调用。" type="info" :closable="false" />
    <el-row class="toolbar"><el-button type="primary" @click="openCreate">创建应用</el-button><el-button @click="reload">刷新</el-button></el-row>
    <el-table v-loading="loading" :data="items" @row-click="openDetail">
      <el-table-column prop="name" label="应用" min-width="180" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="平台限制" width="110"><template #default="{ row }">{{ row.adminDisabled ? '已禁用' : '正常' }}</template></el-table-column>
      <el-table-column prop="configStatus" label="配置" width="130" /><el-table-column prop="currentConfigVersionId" label="当前配置版本" min-width="150" />
      <el-table-column label="操作" width="220"><template #default="{ row }"><el-button link type="primary" @click.stop="openDetail(row)">配置</el-button><el-button link :disabled="!['SPEAK_ONLY', 'CHAT'].includes(row.currentMode) || row.status !== 'ACTIVE' || !!row.adminDisabled" @click.stop="debug(row)">进入调试</el-button><el-button link :disabled="row.status !== 'ACTIVE' && !!row.adminDisabled" :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click.stop="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="createOpen" title="创建应用" width="460px"><el-form :model="createForm" label-width="72px"><el-form-item label="名称" required><el-input v-model="createForm.name" maxlength="100" /></el-form-item><el-form-item label="说明"><el-input v-model="createForm.description" type="textarea" maxlength="1000" /></el-form-item></el-form><template #footer><el-button @click="createOpen=false">取消</el-button><el-button type="primary" :disabled="!createForm.name.trim()" @click="create">创建</el-button></template></el-dialog>
    <el-drawer v-model="detailOpen" title="应用配置" size="760px"><template v-if="detail"><p>状态：{{ detail.status }} · 修订：{{ detail.revision }}</p><p v-if="detail.currentConfig">当前版本：{{ detail.currentConfig.versionNo }}（{{ detail.currentConfig.configVersionId }}）</p><el-form label-width="130px">
      <el-form-item label="模式"><el-radio-group v-model="config.mode"><el-radio-button value="SPEAK_ONLY">纯播报</el-radio-button><el-radio-button value="CHAT">对话</el-radio-button></el-radio-group></el-form-item>
      <el-form-item label="角色版本"><el-select v-model="config.avatarVersionId" filterable class="full"><el-option v-for="item in choices.avatars" :key="item.versionId" :value="item.versionId" :label="`${item.name} · v${item.versionNo} · ${item.visibility}`" /></el-select></el-form-item>
      <el-form-item label="官方声音版本"><el-select v-model="config.voiceVersionId" filterable class="full"><el-option v-for="item in choices.voices" :key="item.versionId" :value="item.versionId" :label="`${item.name} · ${item.voiceAlias} · v${item.versionNo}`" /></el-select></el-form-item>
      <template v-if="config.mode === 'CHAT'">
        <el-form-item label="LLM Relay"><el-select v-model="config.llmRelayVersionId" filterable class="full"><el-option v-for="item in llmRelays" :key="item.versionId" :value="item.versionId" :label="`${item.name} · ${item.versionId}`" /></el-select></el-form-item>
        <el-form-item label="ASR Relay"><el-select v-model="config.asrRelayVersionId" clearable filterable class="full"><el-option v-for="item in asrRelays" :key="item.versionId" :value="item.versionId" :label="`${item.name} · ${item.versionId}`" /></el-select></el-form-item>
        <el-form-item label="模型 ID"><el-input v-model="config.llmModelId" maxlength="128" placeholder="开发者 Relay 使用的模型 ID" /></el-form-item>
        <el-form-item label="System Prompt"><el-input v-model="config.systemPrompt" type="textarea" :rows="4" maxlength="32768" show-word-limit /></el-form-item>
        <el-form-item label="模型参数"><el-input-number v-model="config.temperature" :min="0" :max="2" :step="0.1" /> <span class="hint">temperature</span><el-input-number v-model="config.maxOutputTokens" :min="1" :max="8192" /> <span class="hint">maxOutputTokens</span></el-form-item>
        <el-form-item label="模型能力"><el-checkbox v-model="config.image">图片</el-checkbox><el-checkbox v-model="config.tool">Tool</el-checkbox></el-form-item>
        <el-form-item label="Skills"><el-select v-model="config.skillVersionIds" multiple filterable clearable class="full"><el-option v-for="item in choices.skills" :key="item.versionId" :value="item.versionId" :label="`${item.name} · ${item.skillType}${item.toolName ? ' · ' + item.toolName : ''}`" /></el-select></el-form-item>
        <el-form-item label="Context 策略"><el-input v-model="config.contextPolicyJson" type="textarea" :rows="7" placeholder='{"enabled":false}' /><div class="hint">默认关闭；启用时需配置来源、采集范围、DOM 排除、次数和高亮规则。</div><el-button link @click="loadContextExample">加载安全示例</el-button></el-form-item>
        <el-form-item label="运行上限"><el-input-number v-model="config.toolCallsPerTurn" :min="0" :max="20" /> <span class="hint">每轮 Tool 次数</span><el-input-number v-model="config.capturesPerTurn" :min="0" :max="20" /> <span class="hint">每轮采集次数</span></el-form-item>
      </template>
    </el-form><el-empty v-if="!choices.voices.length" description="暂无已发布官方声音，等待管理员发布后再配置应用。" /><el-button type="primary" :disabled="detail.status !== 'ACTIVE' || !!detail.adminDisabled || !config.avatarVersionId || !config.voiceVersionId || (config.mode === 'CHAT' && (!config.llmRelayVersionId || !config.llmModelId.trim()))" @click="publish">发布新配置版本</el-button><el-divider>Application Secret</el-divider><el-alert title="只在可信后端保存。创建或重置后只显示一次；重置不提前撤销已签发的浏览器授权。" type="warning" :closable="false" /><el-button class="secret-action" :disabled="detail.status !== 'ACTIVE' || !!detail.adminDisabled" @click="resetSecret">{{ secrets.some((item: AccessKeySummary) => item.status === 'ACTIVE') ? '重置 Secret' : '创建 Secret' }}</el-button><el-table :data="secrets"><el-table-column prop="name" label="名称" /><el-table-column prop="displaySuffix" label="末尾" width="100" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="130"><template #default="{ row }"><el-button link :disabled="row.status !== 'ACTIVE'" @click="changeSecret(row, 'disable')">停用</el-button><el-button link :disabled="row.status === 'DELETED'" @click="changeSecret(row, 'delete')">删除</el-button></template></el-table-column></el-table><el-divider>历史版本</el-divider><el-table :data="detail.versions"><el-table-column prop="versionNo" label="版本" width="90" /><el-table-column prop="avatarVersionId" label="角色版本" /><el-table-column prop="voiceVersionId" label="声音版本" /><el-table-column prop="mode" label="模式" /></el-table></template></el-drawer>
    <el-dialog v-model="secretOpen" title="立即保存 Application Secret" width="580px" @closed="issuedSecret = ''"><el-alert title="关闭后无法再次查看；响应丢失时请重新重置。" type="warning" :closable="false" /><el-input class="secret-action" :model-value="issuedSecret" readonly /><template #footer><el-button @click="copySecret">复制</el-button><el-button type="primary" @click="secretOpen = false">已保存</el-button></template></el-dialog>
    <el-dialog v-model="debugOpen" title="应用调试" width="720px" @closed="closeDebug"><el-alert :title="debugMode === 'CHAT' ? '点击发送后调用开发者 Relay；可能产生模型费用。' : '仅在点击播报后才触发合成；应用配置保存本身不会发起调用。'" type="warning" :closable="false" /><RuntimeSpeechPlayer v-if="debugToken" :token="debugToken" :mode="debugMode" class="player" /></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeApplicationStatus, createApplication, createApplicationDebugSession, getApplication, listApplicationChoices, listApplications, publishApplicationConfig, type ApplicationChoices, type ApplicationConfigInput, type ApplicationDetail, type ApplicationSummary } from '@/api/application'
import { closeDebugSession, mintDebugSessionToken } from '@/api/asset/official-voice'
import RuntimeSpeechPlayer from '@/views/voice/official/RuntimeSpeechPlayer.vue'
import { changeApplicationSecret, listApplicationSecrets, resetApplicationSecret, type AccessKeySummary } from '@/api/developer/access-key'
import { useRoute } from 'vue-router'

const route = useRoute()
const loading = ref(false), createOpen = ref(false), detailOpen = ref(false), debugOpen = ref(false), items = ref<ApplicationSummary[]>([]), detail = ref<ApplicationDetail>()
const debugToken = ref(''), debugSessionId = ref(''), debugMode = ref<'CHAT' | 'SPEAK_ONLY'>('SPEAK_ONLY')
const secrets = ref<AccessKeySummary[]>([]), secretOpen = ref(false), issuedSecret = ref('')
const choices = reactive<ApplicationChoices>({ avatars: [], voices: [], relays: [], skills: [] })
const createForm = reactive({ name: '', description: '' })
const config = reactive({
  mode: 'SPEAK_ONLY' as 'CHAT' | 'SPEAK_ONLY', avatarVersionId: '', voiceVersionId: '',
  llmRelayVersionId: '', asrRelayVersionId: '', llmModelId: '', systemPrompt: '',
  temperature: 0.7, maxOutputTokens: 1024, image: false, tool: false,
  skillVersionIds: [] as string[], contextPolicyJson: '{"enabled":false}',
  toolCallsPerTurn: 4, capturesPerTurn: 2
})
function objectValue(value: unknown): Record<string, unknown> {
  if (typeof value === 'string') { try { value = JSON.parse(value) } catch { return {} } }
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}
function relaySupports(item: ApplicationChoices['relays'][number], capability: 'llm' | 'asr') {
  return objectValue(item.capabilities)[capability] === true
}
const llmRelays = computed(() => choices.relays.filter((item: ApplicationChoices['relays'][number]) => relaySupports(item, 'llm')))
const asrRelays = computed(() => choices.relays.filter((item: ApplicationChoices['relays'][number]) => relaySupports(item, 'asr')))
function loadContextExample() {
  config.contextPolicyJson = JSON.stringify({
    enabled: true, modes: ['EXPLICIT'], sources: ['HYBRID'],
    captureScope: { allow: ['#app'], deny: [], allowViewport: true },
    dom: { allow: ['#app'], deny: [], excludePassword: true },
    fullPageEnabled: false, resultMode: 'PARTIAL', highlightMode: 'EVENT_ONLY', maxCapturesPerTurn: 2
  }, null, 2)
}
function reload() { loading.value = true; listApplications().then(res => { items.value = res.data?.items || []; const applicationId = typeof route.query.applicationId === 'string' ? route.query.applicationId : ''; const avatarVersionId = typeof route.query.avatarVersionId === 'string' ? route.query.avatarVersionId : ''; const target = items.value.find((item: ApplicationSummary) => item.applicationId === applicationId); if (target) openDetail(target, avatarVersionId) }).finally(() => { loading.value = false }) }
function openCreate() { createForm.name = ''; createForm.description = ''; createOpen.value = true }
function create() { createApplication({ name: createForm.name.trim(), description: createForm.description.trim() || undefined }).then(() => { createOpen.value = false; ElMessage.success('应用已创建，请发布首个配置版本。'); reload() }) }
function openDetail(row: ApplicationSummary, adoptedAvatarVersionId = '') {
  Promise.all([getApplication(row.applicationId), listApplicationChoices(), listApplicationSecrets(row.applicationId)]).then(([app, resources, keys]) => {
    detail.value = app.data; secrets.value = keys.data || []
    Object.assign(choices, resources.data || { avatars: [], voices: [], relays: [], skills: [] })
    const current = app.data?.currentConfig
    config.mode = current?.mode || 'SPEAK_ONLY'
    config.avatarVersionId = adoptedAvatarVersionId || current?.avatarVersionId || ''
    config.voiceVersionId = current?.voiceVersionId || ''
    config.llmRelayVersionId = current?.llmRelayVersionId || ''
    config.asrRelayVersionId = current?.asrRelayVersionId || ''
    config.llmModelId = current?.llmModelId || ''
    config.systemPrompt = current?.systemPrompt || ''
    const parameters = objectValue(current?.llmParameters), capabilities = objectValue(current?.llmCapabilities)
    const limits = objectValue(current?.runtimeLimits)
    config.temperature = Number(parameters.temperature ?? 0.7)
    config.maxOutputTokens = Number(parameters.maxOutputTokens ?? 1024)
    config.image = !!capabilities.image; config.tool = !!capabilities.tool
    config.skillVersionIds = (current?.skills || []).filter(item => item.enabled).map(item => item.skillVersionId)
    config.contextPolicyJson = JSON.stringify(current?.contextPolicy ? objectValue(current.contextPolicy) : { enabled: false }, null, 2)
    config.toolCallsPerTurn = Number(limits.toolCallsPerTurn ?? 4)
    config.capturesPerTurn = Number(limits.capturesPerTurn ?? 2)
    detailOpen.value = true
  })
}
function resetSecret() { if (!detail.value) return; const app = detail.value; ElMessageBox.prompt('旧 Secret 的新后端请求会立即失效。请输入新 Secret 名称。', '重置 Application Secret', { inputPattern: /\S/, inputErrorMessage: '名称不能为空', inputValue: app.name + ' Secret' }).then(({ value }: { value: string }) => resetApplicationSecret(app.applicationId, value.trim()).then(res => { openDetail(app); if (res.data?.secret) { issuedSecret.value = res.data.secret; secretOpen.value = true } else ElMessage.warning('操作已完成，但本次无法再显示完整 Secret；请重新重置。') })) }
function changeSecret(row: AccessKeySummary, action: 'disable' | 'delete') { if (!detail.value) return; const app = detail.value; ElMessageBox.confirm(`确认${action === 'disable' ? '停用' : '删除'}该 Secret？`, 'Application Secret').then(() => changeApplicationSecret(app.applicationId, row.keyId, action).then(() => openDetail(app))) }
function copySecret() { navigator.clipboard.writeText(issuedSecret.value).then(() => ElMessage.success('已复制')) }
function publish() {
  if (!detail.value) return
  let contextPolicy: Record<string, unknown>
  try { contextPolicy = JSON.parse(config.contextPolicyJson) }
  catch { ElMessage.error('Context 策略不是有效 JSON'); return }
  if (!contextPolicy || typeof contextPolicy !== 'object' || Array.isArray(contextPolicy)) {
    ElMessage.error('Context 策略必须是对象'); return
  }
  const input: ApplicationConfigInput = {
    mode: config.mode, avatarVersionId: config.avatarVersionId, voiceVersionId: config.voiceVersionId,
    contextPolicy
  }
  if (config.mode === 'CHAT') {
    input.llmRelayVersionId = config.llmRelayVersionId
    input.asrRelayVersionId = config.asrRelayVersionId || undefined
    input.llmModelId = config.llmModelId.trim()
    input.systemPrompt = config.systemPrompt.trim() || undefined
    input.llmParameters = { temperature: config.temperature, maxOutputTokens: config.maxOutputTokens }
    input.llmCapabilities = { image: config.image, tool: config.tool }
    input.skills = config.skillVersionIds.map((skillVersionId: string, sortOrder: number) => ({ skillVersionId, enabled: true, sortOrder }))
    input.runtimeLimits = { toolCallsPerTurn: config.toolCallsPerTurn, capturesPerTurn: config.capturesPerTurn }
  } else input.contextPolicy = { enabled: false }
  publishApplicationConfig(detail.value.applicationId, detail.value.revision, input).then(() => {
    ElMessage.success('已发布固定配置版本。'); openDetail(detail.value!)
  })
}
function toggle(row: ApplicationSummary) { const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; ElMessageBox.prompt(status === 'DISABLED' ? '停用会撤销正在使用的调试会话。请输入原因。' : '请输入重新启用原因。', `${status === 'DISABLED' ? '停用' : '启用'}应用`, { inputPattern: /\S/, inputErrorMessage: '原因不能为空' }).then(({ value }: { value: string }) => changeApplicationStatus(row.applicationId, row.revision, status, value).then(() => { ElMessage.success('应用状态已更新。'); reload() })) }
function debug(row: ApplicationSummary) { debugMode.value = row.currentMode === 'CHAT' ? 'CHAT' : 'SPEAK_ONLY'; createApplicationDebugSession(row.applicationId).then(res => { const sessionId = res.sessionId; if (!sessionId) throw new Error('DEBUG Session 未返回 ID'); debugSessionId.value = sessionId; return mintDebugSessionToken(sessionId) }).then(res => { debugToken.value = res.token || ''; if (!debugToken.value) throw new Error('DEBUG Session 未返回授权'); debugOpen.value = true }).catch(error => { closeDebug(); throw error }) }
function closeDebug() { const sessionId = debugSessionId.value; debugSessionId.value = ''; debugToken.value = ''; if (sessionId) closeDebugSession(sessionId).catch(() => {}) }
reload()
</script>

<style scoped>
.toolbar { margin: 16px 0; gap: 8px; }.full { width: 100%; }.player { margin-top: 16px; }.secret-action { margin-top: 14px; }.hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 8px; }
</style>
