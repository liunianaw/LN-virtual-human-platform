<template>
  <div class="app-container">
    <header class="console-page-heading"><div><h1>官方声音</h1><p>维护候选、试听与发布，声音与虚拟形象独立管理。</p></div><router-link to="/operations/tasks?tab=voices">查看语音调用 ↗</router-link></header>
    <el-alert title="候选声音不会出现在开发者目录；发布后才可被 Application 使用。" type="info" :closable="false" class="mb16" />
    <el-card :header="editingVoiceId ? '保存新的声音版本' : '保存官方声音候选'" class="mb16">
      <el-radio-group v-model="voiceMode" class="mb16" @change="changeVoiceMode">
        <el-radio-button value="NORMAL">普通声音发布</el-radio-button>
        <el-radio-button value="CLONE">声音克隆</el-radio-button>
      </el-radio-group>
      <el-alert v-if="voiceMode === 'CLONE' && !modeServices.length" title="暂无支持声音克隆的已启用官方服务，请先在官方服务中配置。" type="info" :closable="false" class="mb16" />
      <el-form label-width="100px" inline>
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="官方服务" required><el-select v-model="form.officialServiceId" @change="selectService"><el-option v-for="item in modeServices" :key="item.serviceId" :label="`${item.name}（${item.modelId}）`" :value="item.serviceId" /></el-select></el-form-item>
        <el-form-item v-if="voiceMode === 'NORMAL'" label="音色" required><el-input v-model.trim="form.voiceAlias" placeholder="请输入服务支持的音色标识" /></el-form-item>
        <el-form-item label="语言"><el-select v-model="form.language"><el-option v-for="language in selectedService?.capability.languages || []" :key="language" :value="language" :label="language" /></el-select></el-form-item>
        <el-form-item v-for="(range, name) in selectedService?.capability.parameters || {}" :key="name" :label="String(name)"><el-input-number v-model="form.parameters[name]" :min="range.minimum" :max="range.maximum" :step="0.1" /></el-form-item>
        <template v-if="voiceMode === 'CLONE' && selectedService?.capability.referenceVoice"><el-form-item label="参考音频" required><el-select v-model="form.referenceAssetId" clearable><el-option v-for="item in references" :key="item.id" :value="item.id" :label="item.name" /></el-select><input type="file" accept="audio/wav" @change="uploadReference" /><span v-if="selectedService?.capability.referenceMaxDurationMs">参考音频须为单声道 PCM16 / 24 kHz WAV，最长 {{ selectedService.capability.referenceMaxDurationMs / 1000 }} 秒；超长素材须更换后再试听。</span></el-form-item><el-form-item label="参考文本" required><el-input v-model="form.referenceText" maxlength="2000" placeholder="请输入参考音频中的文字" /></el-form-item></template>
        <el-form-item label="备用声音"><el-select v-model="form.fallbackVoiceVersionId" clearable placeholder="不使用备用" @visible-change="showFallbacks" @change="rememberFallback"><el-option v-for="item in publishedVoices" :key="item.voiceId" :value="item.currentVersionId!" :label="item.name" /><template #footer><div class="fallback-pages"><el-button size="small" :disabled="fallbackPage === 1 || fallbackLoading" @click="loadFallbacks(fallbackPage - 1)">上一页</el-button><span>{{ fallbackPage }} / {{ Math.max(1, Math.ceil(fallbackTotal / 20)) }}</span><el-button size="small" :disabled="fallbackPage * 20 >= fallbackTotal || fallbackLoading" @click="loadFallbacks(fallbackPage + 1)">下一页</el-button></div></template></el-select></el-form-item>
        <el-form-item v-if="form.fallbackVoiceVersionId"><el-checkbox v-model="form.allowVoiceChange">允许故障时改变音色（保存时校验兼容性）</el-checkbox></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" maxlength="1000" /></el-form-item>
        <el-form-item><el-button type="primary" @click="saveCandidate">{{ editingVoiceId ? '保存新版本' : '保存候选' }}</el-button><el-button v-if="editingVoiceId" @click="editingVoiceId = ''">取消编辑</el-button></el-form-item>
      </el-form>
    </el-card>
    <div class="voice-resource-links"><router-link to="/system/official-services">管理所属服务 ↗</router-link><router-link to="/system/public-assets?kind=voices">查看引用、下架与删除 ↗</router-link></div>
    <el-table :data="voices" v-loading="loading" border>
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="status" label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'warning'">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="voiceAlias" label="当前音色" width="130" />
      <el-table-column label="操作" min-width="220"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">版本与发布</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page="pageNum" :limit="pageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="changePage" />
    <el-dialog v-model="detailOpen" :title="detail?.name || '官方声音'" width="720px">
      <el-alert title="试听只执行当前候选，不使用备用；云服务可能收费，测试音与自部署服务不代表免费平台业务播报。" type="warning" :closable="false" /><el-button :disabled="auditionBusy" @click="newAudition">开始新的试听请求</el-button><el-input v-model="auditionText" maxlength="200" class="mt16" /><audio v-if="auditionUrl" :src="auditionUrl" controls class="mt16" @ended="auditionComplete = true" />
      <el-table :data="detail?.versions || []" border><el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="voiceAlias" label="音色" /><el-table-column prop="providerType" label="执行类型" /><el-table-column label="主备与参考"><template #default="{ row }"><div>{{ row.fallbackVoiceVersionId ? '备用版本：'+row.fallbackVoiceVersionId : '无备用' }} {{ row.allowVoiceChange ? '允许音色变化' : '' }}</div><div v-if="row.referenceAssetId">参考音频：{{ referenceName(row.referenceAssetId) }}</div></template></el-table-column><el-table-column label="操作" width="220"><template #default="{ row }"><el-button link @click="editVersion(row)">编辑为新版本</el-button><el-button link type="primary" :loading="auditionBusy" @click="audition(row.versionId)">试听</el-button><el-button link type="success" :disabled="detail?.currentVersionId === row.versionId || auditionVersion !== row.versionId || !auditionComplete" @click="publish(row)">发布</el-button></template></el-table-column></el-table>
    </el-dialog>
  </div>
</template>
<script setup lang="ts" name="OfficialVoices">
import { createRequestId } from '@ln-avatar/sdk'
import { voiceReferences, uploadVoiceReference, type OfficialVoiceVersion, createOfficialVoiceVersion, auditionOfficialVoice, createOfficialVoice, getOfficialVoice, listOfficialVoiceServices, listOfficialVoices, publishOfficialVoice, type OfficialVoice, type OfficialVoiceService, type OfficialVoiceSummary } from '@/api/asset/official-voice'
const { proxy } = getCurrentInstance()
const pageNum = ref(1), pageSize = ref(20), total = ref(0)
const loading = ref(false); const services = ref<OfficialVoiceService[]>([]); const voices = ref<OfficialVoiceSummary[]>([]); const detail = ref<OfficialVoice>(); const detailOpen = ref(false)
const editingVoiceId = ref(''); const editingRevision = ref('');
const voiceMode = ref<'NORMAL' | 'CLONE'>('NORMAL')
const auditionKey = ref(createRequestId()); const auditionRequest = ref('');
const references = ref<{ id: string; name: string }[]>([])
async function uploadReference(event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (!file || voiceMode.value !== 'CLONE' || !selectedService.value?.capability.referenceVoice) return; const serviceId = form.officialServiceId; const result = await uploadVoiceReference(file); references.value = (await voiceReferences()).data || []; if (voiceMode.value === 'CLONE' && form.officialServiceId === serviceId) form.referenceAssetId = result.data?.referenceAssetId || '' }
const auditionText = ref('你好，这是官方声音试听。'); const auditionUrl = ref(''); const auditionVersion = ref(''); const auditionComplete = ref(false); const auditionBusy = ref(false)
function clearAudition() { if (auditionUrl.value) URL.revokeObjectURL(auditionUrl.value); auditionUrl.value = ''; auditionVersion.value = ''; auditionComplete.value = false }
function audition(versionId: string) { if (!detail.value || auditionBusy.value) return; proxy?.$modal.confirm('试听可能产生云服务费用；相同请求重读不会重新合成。确认继续？').then(async () => { clearAudition(); auditionBusy.value = true; try { const request = `${versionId}:${auditionText.value}`; if (auditionRequest.value !== request) { auditionKey.value = createRequestId(); auditionRequest.value = request }; const blob = await auditionOfficialVoice(detail.value!.voiceId, versionId, auditionText.value, auditionKey.value); auditionUrl.value = URL.createObjectURL(blob); auditionVersion.value = versionId } finally { auditionBusy.value = false } }) }
function newAudition() { clearAudition(); auditionRequest.value = ''; auditionKey.value = createRequestId(); proxy?.$modal.msgSuccess('下次试听将创建新请求，可能产生新费用。') }
onBeforeUnmount(clearAudition)
const form = reactive({ name: '', description: '', officialServiceId: '', voiceAlias: '', language: 'zh-CN', parameters: {} as Record<string, number>, fallbackVoiceVersionId: '', allowVoiceChange: false, referenceAssetId: '', referenceText: '' })
const selectedService = computed(() => services.value.find((item: OfficialVoiceService) => item.serviceId === form.officialServiceId))
const modeServices = computed(() => services.value.filter((item: OfficialVoiceService) => voiceMode.value === 'CLONE' ? item.capability.referenceVoice : item.availableVoiceAliases.length > 0))
function changeVoiceMode() { if (!modeServices.value.some((item: OfficialVoiceService) => item.serviceId === form.officialServiceId)) form.officialServiceId = modeServices.value[0]?.serviceId || ''; selectService() }
const fallbackPage = ref(1), fallbackTotal = ref(0), fallbackLoading = ref(false)
const fallbackOptions = ref<OfficialVoiceSummary[]>([]), chosenFallback = ref<OfficialVoiceSummary>()
const publishedVoices = computed<OfficialVoiceSummary[]>(() => {
  const options = fallbackOptions.value.filter((v: OfficialVoiceSummary) => v.status === 'PUBLISHED' && v.currentVersionId)
  if (form.fallbackVoiceVersionId && !options.some((v: OfficialVoiceSummary) => v.currentVersionId === form.fallbackVoiceVersionId)) {
    const selected = chosenFallback.value?.currentVersionId === form.fallbackVoiceVersionId ? chosenFallback.value : undefined
    return [...options, selected || { voiceId: 'selected-fallback', name: '当前备用版本 ' + form.fallbackVoiceVersionId, currentVersionId: form.fallbackVoiceVersionId, status: 'PUBLISHED', revision: '' }]
  }
  return options
})
function rememberFallback(value: string) { chosenFallback.value = publishedVoices.value.find((v: OfficialVoiceSummary) => v.currentVersionId === value) }
function showFallbacks(visible: boolean) { if (visible) loadFallbacks(1) }
async function loadFallbacks(page: number) {
  if (fallbackLoading.value) return
  fallbackLoading.value = true
  try { const response = await listOfficialVoices({ pageNum: page, pageSize: 20, status: 'PUBLISHED' }); fallbackOptions.value = response.data?.items || []; fallbackTotal.value = response.data?.total || 0; fallbackPage.value = page }
  finally { fallbackLoading.value = false }
}
function selectService() { form.voiceAlias = selectedService.value?.availableVoiceAliases[0] || ''; form.language = selectedService.value?.capability.languages[0] || 'zh-CN'; const ranges: Record<string, { defaultValue: number }> = selectedService.value?.capability.parameters || {}; form.parameters = Object.fromEntries(Object.entries(ranges).map(([name, range]) => [name, range.defaultValue])); form.referenceAssetId = ''; form.referenceText = '' }
let listRequest = 0
function reload() { const request = ++listRequest; loading.value = true; Promise.all([listOfficialVoiceServices(), listOfficialVoices({ pageNum: pageNum.value, pageSize: pageSize.value })]).then(([service, voice]) => { if (request !== listRequest) return; services.value = service.data || []; voices.value = voice.data?.items || []; total.value = voice.data?.total || 0; if (!form.officialServiceId && modeServices.value[0]) { form.officialServiceId = modeServices.value[0].serviceId; selectService() } }).finally(() => { if (request === listRequest) loading.value = false }) }
function changePage({ page, limit }: { page: number; limit: number }) { pageNum.value = pageSize.value === limit ? page : 1; pageSize.value = limit; reload() }
function input() { const clone = voiceMode.value === 'CLONE'; if (!selectedService.value || !form.name.trim() || (clone ? !selectedService.value.capability.referenceVoice || !form.referenceAssetId || !form.referenceText.trim() : !form.voiceAlias.trim())) return undefined; return { name: form.name.trim(), description: form.description.trim() || undefined, officialServiceId: form.officialServiceId, expectedServiceRevision: selectedService.value.revision, voiceAlias: clone ? `reference:${form.referenceAssetId}` : form.voiceAlias.trim(), language: form.language, parameters: form.parameters, fallbackVoiceVersionId: form.fallbackVoiceVersionId || undefined, allowVoiceChange: form.allowVoiceChange, referenceAssetId: clone ? form.referenceAssetId : undefined, referenceText: clone ? form.referenceText.trim() : undefined } }
function saveCandidate() { const data = input(); if (!data) return proxy?.$modal.msgWarning(voiceMode.value === 'CLONE' ? '请填写名称、选择支持声音克隆的官方服务，并提供参考音频及参考文本。' : '请填写名称、选择已启用的官方服务，并输入音色标识。'); (editingVoiceId.value ? createOfficialVoiceVersion(editingVoiceId.value, editingRevision.value, data) : createOfficialVoice(data)).then(() => { proxy?.$modal.msgSuccess('候选声音已保存，尚未公开。'); form.name = ''; form.description = ''; editingVoiceId.value = ''; reload() }) }
function referenceName(id: string) { return references.value.find((item: { id: string; name: string }) => item.id === id)?.name || id }
function editVersion(version: OfficialVoiceVersion) { if (!detail.value) return; if (!services.value.some((service: OfficialVoiceService) => service.serviceId === version.officialServiceId)) return proxy?.$modal.msgWarning('原服务不可用，请先启用或登记合适的服务。'); voiceMode.value = version.referenceAssetId ? 'CLONE' : 'NORMAL'; editingVoiceId.value = detail.value.voiceId; editingRevision.value = detail.value.revision; Object.assign(form, { name: detail.value.name, description: detail.value.description || '', officialServiceId: version.officialServiceId, voiceAlias: version.referenceAssetId ? '' : version.voiceAlias, language: version.language || 'zh-CN', parameters: { ...version.parameters }, referenceAssetId: version.referenceAssetId || '', referenceText: version.referenceText || '', fallbackVoiceVersionId: version.fallbackVoiceVersionId || '', allowVoiceChange: version.allowVoiceChange || false }); detailOpen.value = false }
function openDetail(row: OfficialVoiceSummary) { clearAudition(); getOfficialVoice(row.voiceId).then((response: { data?: OfficialVoice }) => { detail.value = response.data; detailOpen.value = true }) }
function publish(version: { versionId: string }) { if (!detail.value) return; proxy?.$modal.confirm('确认发布该官方声音版本？新 Session 才会使用新的当前版本。').then(() => publishOfficialVoice(detail.value!.voiceId, version.versionId, detail.value!.revision)).then(() => { proxy?.$modal.msgSuccess('官方声音已发布。'); detailOpen.value = false; reload() }) }
onMounted(() => { reload(); voiceReferences().then(r => references.value = r.data || []) })
</script>
<style scoped>.fallback-pages { display:flex; align-items:center; justify-content:space-between; gap:12px; font-size:14px; }.voice-resource-links { display: flex; flex-wrap: wrap; gap: 24px; margin: 24px 0; font-size: 15px; color: var(--ln-accent); }.mb16 { margin-bottom: 16px; }.mt16 { margin-top: 16px; }</style>
