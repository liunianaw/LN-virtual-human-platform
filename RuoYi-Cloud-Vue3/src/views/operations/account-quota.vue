<template>
  <div class="app-container">
    <el-card header="管理员 · 指定账号额度配置" shadow="never" class="section">
      <el-alert title="并发限额决定能否创建 Session；授予额度按次数、字符或字节计量，不代表人民币费用。付费测试仍须按厂商账单控制预算。" type="warning" :closable="false" />
      <el-form inline class="section"><el-form-item label="账号 ID"><el-input v-model.trim="adminAccountId" placeholder="输入目标用户 ID" /></el-form-item><el-form-item><el-button :loading="adminBusy" @click="loadAdminQuotas">读取账号额度</el-button></el-form-item></el-form>
      <template v-if="loadedAccountId">
        <el-form label-width="140px" class="limit-form">
          <el-form-item label="并发 Session"><el-input-number v-model="adminLimits.maxSessions" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="并发制作"><el-input-number v-model="adminLimits.maxGenerationTasks" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="并发轮次"><el-input-number v-model="adminLimits.maxTurns" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="单文件字节"><el-input-number v-model="adminLimits.maxFileBytes" :min="0" :max="Number.MAX_SAFE_INTEGER" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="adminBusy" @click="saveAdminLimits">保存限额</el-button><span class="form-hint">修订号 {{ adminLimits.revision }}，0 表示首次配置</span></el-form-item>
        </el-form>
        <el-table :data="adminQuota?.balances || []" class="section" border>
          <el-table-column prop="quotaType" label="额度类型" /><el-table-column prop="grantedUnits" label="累计授予" />
          <el-table-column prop="usedUnits" label="已使用" /><el-table-column prop="reservedUnits" label="待结算" />
          <el-table-column prop="availableUnits" label="可用" />
        </el-table>
        <el-form inline class="section">
          <el-form-item label="授予类型"><el-select v-model="grant.quotaType" style="width: 175px" @change="grantKey = ''"><el-option label="TTS 字符" value="TTS_CHAR" /><el-option label="Avatar 制作次数" value="AVATAR_COUNT" /><el-option label="存储字节" value="STORAGE_BYTE" /></el-select></el-form-item>
          <el-form-item label="增加单位"><el-input-number v-model="grant.units" :min="1" :max="1000000000000" @change="grantKey = ''" /></el-form-item>
          <el-form-item label="授予依据"><el-input v-model.trim="grant.reason" maxlength="500" placeholder="填写测试或运营依据" @input="grantKey = ''" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="adminBusy" @click="submitGrant">授予额度</el-button></el-form-item>
        </el-form>
      </template>
    </el-card>
  </div>
</template>
<script setup lang="ts" name="AccountQuotaGovernance">
import { getAccountQuotas, grantAccountQuota, saveAccountLimits, type AccountLimitInput, type QuotaGrantInput, type UsageLimits } from '@/api/developer/usage'
import { ElMessage } from 'element-plus'
const adminAccountId = ref(''), loadedAccountId = ref(''), adminQuota = ref<UsageLimits>(), adminBusy = ref(false)
const adminLimits = reactive<AccountLimitInput>({ revision: 0, maxFileBytes: 0, maxSessions: 0, maxGenerationTasks: 0, maxTurns: 0 })
const grant = reactive<QuotaGrantInput>({ quotaType: 'TTS_CHAR', units: 1, reason: '' })
const grantKey = ref('')
function accountId() {
  if (!/^[1-9]\d*$/.test(adminAccountId.value)) { ElMessage.warning('请输入有效的账号 ID'); return '' }
  return adminAccountId.value
}
async function loadAdminQuotas() {
  const id = accountId()
  if (!id) return
  adminBusy.value = true
  try {
    const result = await getAccountQuotas(id)
    adminQuota.value = result.data
    loadedAccountId.value = id
    const limits = result.data?.limits || {}
    Object.assign(adminLimits, { revision: limits.revision ?? 0, maxFileBytes: limits.maxFileBytes ?? 0,
      maxSessions: limits.maxSessions ?? 0, maxGenerationTasks: limits.maxGenerationTasks ?? 0, maxTurns: limits.maxTurns ?? 0 })
  } finally { adminBusy.value = false }
}
async function saveAdminLimits() {
  if (!loadedAccountId.value || loadedAccountId.value !== accountId()) return
  adminBusy.value = true
  try {
    const result = await saveAccountLimits(loadedAccountId.value, { ...adminLimits })
    adminQuota.value = result.data
    adminLimits.revision = result.data?.limits?.revision ?? adminLimits.revision
    ElMessage.success('账号限额已保存')
  } finally { adminBusy.value = false }
}
async function submitGrant() {
  if (!loadedAccountId.value || loadedAccountId.value !== accountId()) return
  if (!grant.reason.trim()) { ElMessage.warning('请填写授予依据'); return }
  if (!grantKey.value) grantKey.value = crypto.randomUUID()
  adminBusy.value = true
  try {
    const result = await grantAccountQuota(loadedAccountId.value, { ...grant }, grantKey.value)
    if (adminQuota.value) adminQuota.value.balances = result.data?.balances || []
    grantKey.value = ''
    grant.reason = ''
    ElMessage.success(result.data?.applied ? '额度已授予' : '该请求已处理，无重复授予')
  } finally { adminBusy.value = false }
}
</script>
<style scoped>.section { margin-top: 20px; }.limit-form { margin-top: 20px; }.form-hint { margin-left: 12px; color: var(--el-text-color-secondary); }</style>
