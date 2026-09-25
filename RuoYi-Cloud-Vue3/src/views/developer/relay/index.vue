<template>
  <div class="app-container">
    <el-alert title="Relay 只连接你的后端，LLM/ASR 厂商 Key 留在你的后端。连接测试仅读取无计费的 /capabilities。" type="info" :closable="false" />
    <div class="toolbar"><el-button type="primary" @click="createOpen = true">创建 LLM/ASR Relay</el-button><el-button @click="reload">刷新</el-button></div>
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="grantMode" label="应用授权" min-width="160" />
      <el-table-column label="连接测试" width="120"><template #default="{ row }">{{ row.lastTestStatus || '未测试' }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.relayId)">管理</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="创建 Relay" width="620px" @closed="createForm.accessToken = ''">
      <el-form label-width="120px">
        <el-form-item label="名称"><el-input v-model="createForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="createForm.description" maxlength="500" /></el-form-item>
        <el-form-item label="后端地址"><el-input v-model="createForm.version.baseUrl" placeholder="https://relay.example.com" /></el-form-item>
        <el-form-item label="Relay Token"><el-input v-model="createForm.accessToken" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="能力"><el-checkbox v-model="createForm.version.capabilities.llm">LLM</el-checkbox><el-checkbox v-model="createForm.version.capabilities.asr">ASR</el-checkbox></el-form-item>
        <el-form-item label="应用授权"><el-select v-model="createForm.grantMode" class="full"><el-option label="本账号所有应用" value="ALL_ACCOUNT_APPS" /><el-option label="指定应用" value="EXPLICIT_APPS" /></el-select></el-form-item>
      </el-form>
      <el-alert title="创建后处于停用状态。完成连接测试与授权后再启用。Token 保存后仅显示末尾字符。" type="warning" :closable="false" />
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :disabled="!canSaveCreate" @click="create">创建</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailOpen" :title="detail?.name || 'Relay'" size="680px">
      <template v-if="detail">
        <p>状态：{{ detail.status }} · 修订：{{ detail.authEpoch }} · Token 末尾：{{ detail.tokenSuffix || '—' }}</p>
        <el-alert v-if="detail.adminDisabled" title="管理员已禁用此 Relay，新的调用与 Token 轮换均被拒绝。" type="error" :closable="false" />
        <div class="toolbar"><el-button :loading="testing" @click="test">测试连接</el-button><el-button :disabled="!canEnable" @click="toggle">{{ detail.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><el-button @click="rotate">轮换 Token</el-button><el-button type="danger" @click="remove">删除</el-button></div>
        <p>最近测试：{{ detail.lastTestStatus || '未测试' }} <span v-if="detail.lastTestError">（{{ detail.lastTestError }}）</span></p>

        <el-divider>当前授权</el-divider>
        <el-select v-model="grantMode" class="full"><el-option label="本账号所有应用" value="ALL_ACCOUNT_APPS" /><el-option label="指定应用" value="EXPLICIT_APPS" /></el-select>
        <template v-if="grantMode === 'EXPLICIT_APPS'">
          <el-select v-model="chosenApps" multiple filterable class="full app-select" placeholder="选择账号下的应用">
            <el-option v-for="app in applications" :key="app.applicationId" :label="app.name" :value="app.applicationId" />
          </el-select>
          <div v-for="appId in chosenApps" :key="appId" class="grant-row">
            <span>{{ applicationName(appId) }}</span>
            <el-checkbox-group v-model="scopes[appId]"><el-checkbox value="LLM" :disabled="!currentCaps.llm">LLM</el-checkbox><el-checkbox value="ASR" :disabled="!currentCaps.asr">ASR</el-checkbox></el-checkbox-group>
          </div>
        </template>
        <el-button class="toolbar" :disabled="invalidGrants" @click="saveGrants">保存授权</el-button>

        <el-divider>发布新版本</el-divider>
        <el-form label-width="110px">
          <el-form-item label="后端地址"><el-input v-model="nextVersion.baseUrl" /></el-form-item>
          <el-form-item label="能力"><el-checkbox v-model="nextVersion.capabilities.llm">LLM</el-checkbox><el-checkbox v-model="nextVersion.capabilities.asr">ASR</el-checkbox><el-checkbox v-model="nextVersion.capabilities.image" :disabled="!nextVersion.capabilities.llm">图片</el-checkbox><el-checkbox v-model="nextVersion.capabilities.tool" :disabled="!nextVersion.capabilities.llm">Tool</el-checkbox><el-checkbox v-model="nextVersion.capabilities.cancel">取消</el-checkbox></el-form-item>
          <el-form-item label="超时毫秒"><el-input-number v-model="nextVersion.timeoutMs" :min="1000" :max="120000" /></el-form-item>
          <el-form-item label="响应上限"><el-input-number v-model="nextVersion.maxResponseBytes" :min="1" :max="10485760" /></el-form-item>
        </el-form>
        <el-button :disabled="!nextVersion.baseUrl || (!nextVersion.capabilities.llm && !nextVersion.capabilities.asr)" @click="publish">发布版本</el-button>
        <el-table :data="detail.versions" class="versions"><el-table-column prop="versionNo" label="版本" width="70" /><el-table-column prop="baseUrl" label="地址" min-width="280" /><el-table-column label="能力" width="130"><template #default="{ row }">{{ Object.entries(row.capabilities).filter(([, enabled]) => enabled).map(([name]) => name).join(', ') }}</template></el-table-column></el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { listApplications, type ApplicationSummary } from '@/api/application'
import { addRelayVersion, changeRelayStatus, createRelay, deleteRelay, getRelay, listRelays, rotateRelayToken, setRelayGrants, testRelay, type RelayCreate, type RelayDetail, type RelaySummary, type RelayVersion, type RelayVersionInput } from '@/api/developer/relay'

const blankVersion = (): RelayVersionInput => ({ baseUrl: '', protocolVersion: '1', capabilities: { llm: true, asr: false }, timeoutMs: 30000, maxResponseBytes: 1048576 })
const loading = ref(false), testing = ref(false), createOpen = ref(false), detailOpen = ref(false)
const items = ref<RelaySummary[]>([]), detail = ref<RelayDetail>(), applications = ref<ApplicationSummary[]>([])
const createForm = reactive<RelayCreate>({ name: '', description: '', accessToken: '', grantMode: 'ALL_ACCOUNT_APPS', version: blankVersion() })
const nextVersion = reactive<RelayVersionInput>(blankVersion())
const grantMode = ref<'ALL_ACCOUNT_APPS' | 'EXPLICIT_APPS'>('ALL_ACCOUNT_APPS')
const chosenApps = ref<string[]>([]), scopes = reactive<Record<string, Array<'LLM' | 'ASR'>>>({})
const canSaveCreate = computed(() => !!createForm.name.trim() && !!createForm.accessToken.trim() && !!createForm.version.baseUrl.trim() && !!(createForm.version.capabilities.llm || createForm.version.capabilities.asr))
const canEnable = computed(() => !!detail.value && !detail.value.adminDisabled && (detail.value.status === 'ACTIVE' || detail.value.lastTestStatus === 'SUCCESS' && detail.value.lastTestVersionId === detail.value.currentVersionId))
const currentCaps = computed(() => detail.value?.versions.find((v: RelayVersion) => v.versionId === detail.value?.currentVersionId)?.capabilities || {})
const invalidGrants = computed(() => grantMode.value === 'EXPLICIT_APPS' && chosenApps.value.some((id: string) => !scopes[id]?.length))
watch(chosenApps, (ids: string[]) => { for (const id of ids) if (!scopes[id]) scopes[id] = currentCaps.value.llm ? ['LLM'] : ['ASR'] }, { deep: true })
watch(() => nextVersion.capabilities.llm, (enabled: boolean | undefined) => { if (!enabled) { nextVersion.capabilities.image = false; nextVersion.capabilities.tool = false } })
function applicationName(id: string) { return applications.value.find((app: ApplicationSummary) => app.applicationId === id)?.name || id }

function reload() { loading.value = true; listRelays().then(res => { items.value = res.data?.items || [] }).finally(() => { loading.value = false }) }
function openDetail(id: string) { Promise.all([getRelay(id), listApplications()]).then(([relay, apps]) => {
  detail.value = relay.data; applications.value = apps.data?.items || []
  if (!detail.value) return
  grantMode.value = detail.value.grantMode
  chosenApps.value = detail.value.grants.filter((g: RelayDetail['grants'][number]) => g.status === 'ACTIVE').map((g: RelayDetail['grants'][number]) => g.applicationId)
  for (const key of Object.keys(scopes)) delete scopes[key]
  for (const grant of detail.value.grants) scopes[grant.applicationId] = grant.scopes.filter((scope: 'LLM' | 'ASR') => scope === 'LLM' ? currentCaps.value.llm : currentCaps.value.asr)
  const current = detail.value.versions.find((v: RelayVersion) => v.versionId === detail.value?.currentVersionId)
  Object.assign(nextVersion, current ? { baseUrl: current.baseUrl, protocolVersion: '1', capabilities: { ...current.capabilities }, timeoutMs: current.timeoutMs, maxResponseBytes: current.maxResponseBytes } : blankVersion())
  detailOpen.value = true
}) }
function refresh() { if (detail.value) openDetail(detail.value.relayId); reload() }
function create() { createRelay({ ...createForm, version: { ...createForm.version, capabilities: { ...createForm.version.capabilities } } }).then(res => {
  createOpen.value = false; createForm.accessToken = ''; createForm.name = ''; createForm.description = ''; createForm.version = blankVersion()
  reload(); if (res.data) openDetail(res.data.relayId)
}) }
function publish() { if (!detail.value) return; addRelayVersion(detail.value.relayId, detail.value.authEpoch, { ...nextVersion, capabilities: { ...nextVersion.capabilities } }).then(() => { ElMessage.success('新版本已发布，请重新测试连接。'); refresh() }) }
function saveGrants() { if (!detail.value) return; const grants = grantMode.value === 'EXPLICIT_APPS' ? chosenApps.value.map((applicationId: string) => ({ applicationId, scopes: scopes[applicationId] || (currentCaps.value.llm ? ['LLM' as const] : ['ASR' as const]) })) : []; setRelayGrants(detail.value.relayId, detail.value.authEpoch, { grantMode: grantMode.value, grants }).then(() => { ElMessage.success('授权已更新。'); refresh() }) }
function test() { if (!detail.value) return; testing.value = true; testRelay(detail.value.relayId).then(res => { if (res.data?.success) ElMessage.success('连接、TLS、鉴权、协议和能力检查通过。'); else ElMessage.error(`连接测试失败：${res.data?.errorCode || 'UNKNOWN'}`); refresh() }).finally(() => { testing.value = false }) }
function rotate() { if (!detail.value) return; const row = detail.value; ElMessageBox.prompt('输入平台访问你后端的 Relay Token。旧 Token 将立即失效。', '轮换 Relay Token', { inputType: 'password', inputPattern: /\S/, inputErrorMessage: 'Token 不能为空' }).then(({ value }: { value: string }) => rotateRelayToken(row.relayId, row.authEpoch, value).then(() => { ElMessage.success('Token 已轮换，请重新测试连接。'); refresh() })) }
function toggle() { if (!detail.value) return; const row = detail.value, status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; ElMessageBox.prompt('请输入操作原因。', status === 'ACTIVE' ? '启用 Relay' : '停用 Relay', { inputPattern: /\S/, inputErrorMessage: '原因不能为空' }).then(({ value }: { value: string }) => changeRelayStatus(row.relayId, row.authEpoch, status, value).then(() => { ElMessage.success('状态已更新。'); refresh() })) }
function remove() { if (!detail.value) return; const row = detail.value; ElMessageBox.confirm('删除后不能恢复，已被应用或声音引用的 Relay 无法删除。', '删除 Relay').then(() => deleteRelay(row.relayId, row.authEpoch).then(() => { detailOpen.value = false; detail.value = undefined; reload() })) }
reload()
</script>

<style scoped>.toolbar { display: flex; gap: 8px; margin: 16px 0; }.full { width: 100%; }.app-select { margin: 12px 0; }.grant-row { display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--el-border-color-light); }.versions { margin-top: 14px; }</style>
