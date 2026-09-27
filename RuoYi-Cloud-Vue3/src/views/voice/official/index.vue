<template>
  <div class="app-container">
    <el-alert title="候选声音不会出现在普通用户目录；试听会走专用 DEBUG 会话，实际合成由管理员主动触发。" type="info" :closable="false" class="mb16" />
    <el-card header="保存官方声音候选" class="mb16">
      <el-form label-width="100px" inline>
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="官方服务" required><el-select v-model="form.officialServiceId" @change="selectService"><el-option v-for="item in services" :key="item.serviceId" :label="`${item.name}（${item.modelId}）`" :value="item.serviceId" /></el-select></el-form-item>
        <el-form-item label="音色" required><el-select v-model="form.voiceAlias"><el-option v-for="item in selectedService?.availableVoiceAliases || []" :key="item" :label="item" :value="item" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" maxlength="1000" /></el-form-item>
        <el-form-item><el-button type="primary" @click="saveCandidate">保存候选</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-table :data="voices" v-loading="loading" border>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="voiceAlias" label="当前音色" width="130" />
      <el-table-column label="操作" min-width="300"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">版本与试听</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="detailOpen" :title="detail?.name || '官方声音'" width="720px" @closed="closePreview">
      <el-table :data="detail?.versions || []" border><el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="voiceAlias" label="音色" /><el-table-column label="操作" width="300"><template #default="{ row }"><el-button link @click="preview(row)">创建试听会话</el-button><el-button link type="success" :disabled="detail?.currentVersionId === row.versionId" @click="publish(row)">确认试听后发布</el-button></template></el-table-column></el-table>
      <el-alert class="mt16" type="warning" :closable="false" title="创建会话不等于已合成；仅在下方输入文本并点击播报时才会发起一次试听。" />
      <el-form class="mt16" inline><el-form-item label="已发布角色版本"><el-input v-model="avatarVersionId" placeholder="用于试听的 avatarVersionId" /></el-form-item></el-form>
      <RuntimeSpeechPlayer v-if="previewToken" :token="previewToken" class="mt16" />
    </el-dialog>
  </div>
</template>
<script setup lang="ts" name="OfficialVoices">
import { closeDebugSession, createOfficialVoice, createOfficialVoicePreview, getOfficialVoice, listOfficialVoiceServices, listOfficialVoices, mintDebugSessionToken, publishOfficialVoice, type OfficialVoice, type OfficialVoiceService, type OfficialVoiceSummary } from '@/api/asset/official-voice'
import RuntimeSpeechPlayer from './RuntimeSpeechPlayer.vue'
const { proxy } = getCurrentInstance()
const loading = ref(false); const services = ref<OfficialVoiceService[]>([]); const voices = ref<OfficialVoiceSummary[]>([]); const detail = ref<OfficialVoice>(); const detailOpen = ref(false); const avatarVersionId = ref(''); const previewToken = ref(''); const previewSessionId = ref('')
const form = reactive({ name: '', description: '', officialServiceId: '', voiceAlias: '' })
const selectedService = computed(() => services.value.find((item: OfficialVoiceService) => item.serviceId === form.officialServiceId))
function selectService() { form.voiceAlias = selectedService.value?.availableVoiceAliases[0] || '' }
function reload() { loading.value = true; Promise.all([listOfficialVoiceServices(), listOfficialVoices()]).then(([service, voice]) => { services.value = service.data || []; voices.value = voice.data?.items || []; if (!form.officialServiceId && services.value[0]) { form.officialServiceId = services.value[0].serviceId; selectService() } }).finally(() => { loading.value = false }) }
function input() { if (!selectedService.value || !form.name.trim() || !form.voiceAlias) return undefined; return { name: form.name.trim(), description: form.description.trim() || undefined, officialServiceId: form.officialServiceId, expectedServiceRevision: selectedService.value.revision, voiceAlias: form.voiceAlias, language: 'zh-CN', parameters: {} } }
function saveCandidate() { const data = input(); if (!data) return proxy?.$modal.msgWarning('请填写名称并选择已启用的官方服务和音色。'); createOfficialVoice(data).then(() => { proxy?.$modal.msgSuccess('候选声音已保存，尚未公开。'); form.name = ''; form.description = ''; reload() }) }
function openDetail(row: OfficialVoiceSummary) { previewToken.value = ''; getOfficialVoice(row.voiceId).then((response: { data?: OfficialVoice }) => { detail.value = response.data; detailOpen.value = true }) }
function preview(version: { versionId: string }) { if (!detail.value || !/^\d+$/.test(avatarVersionId.value)) return proxy?.$modal.msgWarning('请输入已发布角色版本 ID。'); closePreview(); previewToken.value = ''; createOfficialVoicePreview(detail.value.voiceId, version.versionId, avatarVersionId.value).then((response: { data?: { sessionId: string } }) => { const sessionId = response.data?.sessionId; if (!sessionId) throw new Error('试听会话未返回 ID'); previewSessionId.value = sessionId; return mintDebugSessionToken(sessionId) }).then(response => { const token = response.token; if (!token) throw new Error('试听会话未返回授权'); previewToken.value = token; proxy?.$modal.msgSuccess('试听会话已就绪；输入文本后才会发起合成。') }) }
function closePreview() { const sessionId = previewSessionId.value; previewSessionId.value = ''; previewToken.value = ''; if (sessionId) closeDebugSession(sessionId).catch(() => {}) }
function publish(version: { versionId: string }) { if (!detail.value) return; proxy?.$modal.confirm('确认已试听该候选版本并发布？发布不会再次发起合成。').then(() => publishOfficialVoice(detail.value!.voiceId, version.versionId, detail.value!.revision)).then(() => { proxy?.$modal.msgSuccess('官方声音已发布。'); detailOpen.value = false; reload() }) }
onMounted(reload)
</script>
<style scoped>.mb16 { margin-bottom: 16px; }.mt16 { margin-top: 16px; }</style>
