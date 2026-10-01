<template>
  <div class="app-container">
    <el-alert title="候选声音不会出现在开发者目录；发布后才可被 Application 使用。" type="info" :closable="false" class="mb16" />
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
      <el-table-column label="操作" min-width="220"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">版本与发布</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="detailOpen" :title="detail?.name || '官方声音'" width="720px">
      <el-alert title="试听会调用官方 TTS 并产生费用，单次不超过 200 字符。" type="warning" :closable="false" /><el-input v-model="auditionText" maxlength="200" class="mt16" /><audio v-if="auditionUrl" :src="auditionUrl" controls class="mt16" @ended="auditionComplete = true" />
      <el-table :data="detail?.versions || []" border><el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="voiceAlias" label="音色" /><el-table-column label="操作" width="220"><template #default="{ row }"><el-button link type="primary" :loading="auditionBusy" @click="audition(row.versionId)">试听</el-button><el-button link type="success" :disabled="detail?.currentVersionId === row.versionId || auditionVersion !== row.versionId || !auditionComplete" @click="publish(row)">发布</el-button></template></el-table-column></el-table>
    </el-dialog>
  </div>
</template>
<script setup lang="ts" name="OfficialVoices">
import { auditionOfficialVoice, createOfficialVoice, getOfficialVoice, listOfficialVoiceServices, listOfficialVoices, publishOfficialVoice, type OfficialVoice, type OfficialVoiceService, type OfficialVoiceSummary } from '@/api/asset/official-voice'
const { proxy } = getCurrentInstance()
const loading = ref(false); const services = ref<OfficialVoiceService[]>([]); const voices = ref<OfficialVoiceSummary[]>([]); const detail = ref<OfficialVoice>(); const detailOpen = ref(false)
const auditionText = ref('你好，这是官方声音试听。'); const auditionUrl = ref(''); const auditionVersion = ref(''); const auditionComplete = ref(false); const auditionBusy = ref(false)
function clearAudition() { if (auditionUrl.value) URL.revokeObjectURL(auditionUrl.value); auditionUrl.value = ''; auditionVersion.value = ''; auditionComplete.value = false }
function audition(versionId: string) { if (!detail.value || auditionBusy.value) return; proxy?.$modal.confirm('试听会调用付费官方 TTS，确认继续？').then(async () => { clearAudition(); auditionBusy.value = true; try { const blob = await auditionOfficialVoice(detail.value!.voiceId, versionId, auditionText.value); auditionUrl.value = URL.createObjectURL(blob); auditionVersion.value = versionId } finally { auditionBusy.value = false } }) }
onBeforeUnmount(clearAudition)
const form = reactive({ name: '', description: '', officialServiceId: '', voiceAlias: '' })
const selectedService = computed(() => services.value.find((item: OfficialVoiceService) => item.serviceId === form.officialServiceId))
function selectService() { form.voiceAlias = selectedService.value?.availableVoiceAliases[0] || '' }
function reload() { loading.value = true; Promise.all([listOfficialVoiceServices(), listOfficialVoices()]).then(([service, voice]) => { services.value = service.data || []; voices.value = voice.data?.items || []; if (!form.officialServiceId && services.value[0]) { form.officialServiceId = services.value[0].serviceId; selectService() } }).finally(() => { loading.value = false }) }
function input() { if (!selectedService.value || !form.name.trim() || !form.voiceAlias) return undefined; return { name: form.name.trim(), description: form.description.trim() || undefined, officialServiceId: form.officialServiceId, expectedServiceRevision: selectedService.value.revision, voiceAlias: form.voiceAlias, language: 'zh-CN', parameters: {} } }
function saveCandidate() { const data = input(); if (!data) return proxy?.$modal.msgWarning('请填写名称并选择已启用的官方服务和音色。'); createOfficialVoice(data).then(() => { proxy?.$modal.msgSuccess('候选声音已保存，尚未公开。'); form.name = ''; form.description = ''; reload() }) }
function openDetail(row: OfficialVoiceSummary) { clearAudition(); getOfficialVoice(row.voiceId).then((response: { data?: OfficialVoice }) => { detail.value = response.data; detailOpen.value = true }) }
function publish(version: { versionId: string }) { if (!detail.value) return; proxy?.$modal.confirm('确认发布该官方声音版本？新 Session 才会使用新的当前版本。').then(() => publishOfficialVoice(detail.value!.voiceId, version.versionId, detail.value!.revision)).then(() => { proxy?.$modal.msgSuccess('官方声音已发布。'); detailOpen.value = false; reload() }) }
onMounted(reload)
</script>
<style scoped>.mb16 { margin-bottom: 16px; }.mt16 { margin-top: 16px; }</style>
