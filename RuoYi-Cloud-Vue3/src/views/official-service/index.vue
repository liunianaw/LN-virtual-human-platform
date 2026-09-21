<template>
  <div class="app-container">
    <el-alert title="服务检查只验证地址、参数和凭证可解析，不会调用厂商或产生费用。保存的密钥不会再显示。" type="info" :closable="false" class="mb16" />
    <el-card header="官方服务配置" class="mb16"><el-form inline label-width="90px">
      <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
      <el-form-item label="能力"><el-select v-model="form.capability" @change="preset"><el-option label="角色制作" value="AVATAR_GENERATION" /><el-option label="官方 TTS" value="TTS" /></el-select></el-form-item>
      <el-form-item label="适配器"><el-input v-model="form.providerCode" readonly /></el-form-item>
      <el-form-item label="模型"><el-input v-model="form.modelId" readonly /></el-form-item>
      <el-form-item label="凭证引用"><el-input v-model="form.secretId" placeholder="已有 p_secret ID" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.endpoint" style="width:360px" /></el-form-item>
      <el-form-item><el-button type="primary" @click="save">保存为停用配置</el-button></el-form-item>
    </el-form></el-card>
    <el-table :data="items" v-loading="loading" border><el-table-column prop="name" label="名称" /><el-table-column prop="capability" label="能力" /><el-table-column prop="modelId" label="模型" /><el-table-column label="凭证" width="110"><template #default="{ row }"><el-tag :type="row.credentialConfigured ? 'success' : 'danger'">{{ row.credentialConfigured ? '已配置' : '缺失' }}</el-tag></template></el-table-column><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" min-width="320"><template #default="{ row }"><el-button link @click="check(row)">检查</el-button><el-button link @click="credential(row)">替换凭证</el-button><el-button link :type="row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button></template></el-table-column></el-table>
  </div>
</template>
<script setup lang="ts" name="OfficialServices">
import { checkOfficialService, createOfficialService, listOfficialServices, replaceOfficialServiceCredential, setOfficialServiceStatus, type OfficialService } from '@/api/asset/official-service'
const { proxy } = getCurrentInstance(); const loading = ref(false); const items = ref<OfficialService[]>([])
const form = reactive({ name: '', capability: 'AVATAR_GENERATION' as OfficialService['capability'], providerCode: 'DASHSCOPE_IMAGE', endpoint: 'https://dashscope.aliyuncs.com', modelId: 'qwen-image-3.0-pro', secretId: '' })
function preset() { const tts = form.capability === 'TTS'; form.providerCode = tts ? 'DASHSCOPE_BEIJING' : 'DASHSCOPE_IMAGE'; form.endpoint = tts ? 'wss://dashscope.aliyuncs.com/api-ws/v1/realtime' : 'https://dashscope.aliyuncs.com'; form.modelId = tts ? 'qwen3-tts-flash-realtime' : 'qwen-image-3.0-pro' }
function reload() { loading.value = true; listOfficialServices().then(r => { items.value = r.data?.items || [] }).finally(() => loading.value = false) }
function save() { if (!form.name.trim() || !/^\d+$/.test(form.secretId)) return proxy?.$modal.msgWarning('请填写名称和已有凭证引用。'); createOfficialService({ ...form, name: form.name.trim(), parameters: {} }).then(() => { proxy?.$modal.msgSuccess('已保存为停用配置。'); form.name = ''; reload() }) }
function check(row: OfficialService) { checkOfficialService(row.serviceId, row.revision).then(r => r.data?.configurationValid ? proxy?.$modal.msgSuccess('配置检查通过；未调用厂商。') : proxy?.$modal.msgError(r.data?.issues?.join('；') || '配置检查未通过')) }
function credential(row: OfficialService) { proxy?.$modal.prompt('输入新的厂商凭证；保存后不会回显。', '替换凭证', { inputType: 'password' }).then(({ value }: { value: string }) => replaceOfficialServiceCredential(row.serviceId, row.revision, value)).then(() => { proxy?.$modal.msgSuccess('凭证已替换。'); reload() }) }
function toggle(row: OfficialService) { const status = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'; proxy?.$modal.prompt(status === 'ACTIVE' ? '确认启用已检查的服务？' : '停用后将拒绝新的调用。', status === 'ACTIVE' ? '启用服务' : '停用服务').then(({ value }: { value: string }) => setOfficialServiceStatus(row.serviceId, row.revision, status, value || (status === 'ACTIVE' ? 'administrator enabled' : 'administrator disabled'))).then(() => reload()) }
onMounted(reload)
</script>
<style scoped>.mb16 { margin-bottom:16px; }</style>
