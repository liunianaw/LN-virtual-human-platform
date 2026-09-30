<template>
  <div class="app-container">
    <el-alert title="逐次调用和日汇总按 UTC 日期查询。厂商未报告的用量显示为不可获取；剩余额度以账本为准。" type="info" :closable="false" />
    <div class="toolbar"><el-button @click="refresh">刷新</el-button></div>
    <el-card header="账号限额与余额" shadow="never">
      <el-descriptions :column="4" border>
        <el-descriptions-item label="并发 Session">{{ limits?.limits.maxSessions ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="并发制作">{{ limits?.limits.maxGenerationTasks ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="并发轮次">{{ limits?.limits.maxTurns ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="单文件字节">{{ limits?.limits.maxFileBytes ?? '未配置' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="limits?.balances || []" class="section">
        <el-table-column prop="quotaType" label="额度" /><el-table-column prop="grantedUnits" label="已授予" />
        <el-table-column prop="usedUnits" label="已使用" /><el-table-column prop="reservedUnits" label="待结算" />
        <el-table-column prop="availableUnits" label="可用" />
      </el-table>
    </el-card>
    <el-card v-if="isAdmin" header="管理员 · 指定账号额度配置" shadow="never" class="section">
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
    <el-card header="用量和调用记录" shadow="never" class="section">
      <el-form inline>
        <el-form-item label="UTC 日期"><el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="起始" end-placeholder="结束" /></el-form-item>
        <el-form-item label="应用 ID"><el-input v-model.trim="applicationId" placeholder="全部应用" clearable /></el-form-item>
        <el-form-item label="能力"><el-select v-model="capability" clearable placeholder="全部" style="width: 135px"><el-option v-for="item in capabilities" :key="item" :value="item" :label="item" /></el-select></el-form-item>
        <el-form-item v-if="tab === 'calls'" label="状态"><el-select v-model="callStatus" clearable placeholder="全部" style="width: 135px"><el-option v-for="item in statuses" :key="item" :value="item" :label="item" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="refresh">查询</el-button></el-form-item>
      </el-form>
      <el-tabs v-model="tab" @tab-change="changeTab">
        <el-tab-pane label="每日汇总" name="daily" />
        <el-tab-pane label="逐次调用" name="calls" />
        <el-tab-pane label="额度预占" name="reservations" />
      </el-tabs>
      <el-table v-if="tab === 'daily'" v-loading="loading" :data="daily">
        <el-table-column prop="usageDate" label="UTC 日期" width="120" /><el-table-column prop="applicationId" label="应用" width="130" />
        <el-table-column prop="capability" label="能力" width="120" /><el-table-column prop="billingOwner" label="费用方" width="120" />
        <el-table-column prop="requestCount" label="请求" width="80" /><el-table-column prop="successCount" label="成功" width="80" />
        <el-table-column prop="unknownCount" label="待核对" width="90" /><el-table-column prop="knownUsageCount" label="有用量" width="90" /><el-table-column prop="knownCostCount" label="有成本" width="90" />
        <el-table-column prop="inputTokens" label="输入 Token" width="110" /><el-table-column prop="outputTokens" label="输出 Token" width="110" />
        <el-table-column prop="inputChars" label="字符" width="90" /><el-table-column label="已知成本" min-width="120"><template #default="{ row }">{{ row.costAmount }} {{ row.currency }}</template></el-table-column>
      </el-table>
      <el-table v-else-if="tab === 'calls'" v-loading="loading" :data="calls">
        <el-table-column prop="callId" label="调用 ID" min-width="170" /><el-table-column prop="createdAt" label="创建时间" min-width="175" />
        <el-table-column prop="applicationId" label="应用" min-width="130" /><el-table-column prop="capability" label="能力" width="105" />
        <el-table-column prop="status" label="状态" width="105" /><el-table-column label="用量" min-width="145"><template #default="{ row }">{{ row.usageAvailable ? `${row.inputTokens ?? 0}/${row.outputTokens ?? 0} Token，${row.inputChars ?? 0} 字` : '不可获取' }}</template></el-table-column>
        <el-table-column label="成本" min-width="110"><template #default="{ row }">{{ row.costAmount == null ? '不可获取' : `${row.costAmount} ${row.currency || ''}` }}</template></el-table-column>
      </el-table>
      <el-table v-else v-loading="loading" :data="reservations">
        <el-table-column prop="reservationId" label="预占 ID" min-width="170" /><el-table-column prop="quotaType" label="额度" width="145" />
        <el-table-column prop="businessType" label="业务" width="125" /><el-table-column prop="reservedUnits" label="预占" width="100" />
        <el-table-column prop="state" label="状态" width="155" /><el-table-column prop="createdAt" label="创建时间" min-width="170" />
      </el-table>
      <el-pagination class="section" layout="prev, pager, next, total" :page-size="20" :total="total" :current-page="pageNum" @current-change="changePage" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { getAccountQuotas, getCalls, getLimits, getReservations, getUsage, grantAccountQuota, saveAccountLimits, type AccountLimitInput, type CallRecord, type QuotaGrantInput, type QuotaReservation, type UsageDaily, type UsageLimits } from '@/api/developer/usage'
import { ElMessage } from 'element-plus'
import useUserStore from '@/store/modules/user'
const capabilities = ['GENERATION', 'LLM', 'ASR', 'TTS', 'TOOL', 'CONTEXT']
const statuses = ['STARTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED']
const limits = ref<UsageLimits>(), daily = ref<UsageDaily[]>([]), calls = ref<CallRecord[]>([]), reservations = ref<QuotaReservation[]>([])
const loading = ref(false), tab = ref('daily'), pageNum = ref(1), total = ref(0)
const range = ref<[string, string]>(), applicationId = ref(''), capability = ref(''), callStatus = ref('')
const isAdmin = computed(() => useUserStore().isAdmin)
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
function params() { return { from: range.value?.[0], to: range.value?.[1], applicationId: applicationId.value || undefined, capability: capability.value || undefined, pageNum: pageNum.value, pageSize: 20 } }
function loadRows() {
  loading.value = true
  const query = tab.value === 'calls' ? getCalls({ ...params(), status: callStatus.value || undefined }) : tab.value === 'reservations' ? getReservations({ pageNum: pageNum.value, pageSize: 20 }) : getUsage(params())
  query.then(result => {
    total.value = result.data?.total || 0
    if (tab.value === 'calls') calls.value = result.data?.items as CallRecord[] || []
    else if (tab.value === 'reservations') reservations.value = result.data?.items as QuotaReservation[] || []
    else daily.value = result.data?.items as UsageDaily[] || []
  }).finally(() => { loading.value = false })
}
function refresh() { pageNum.value = 1; getLimits().then(result => { limits.value = result.data }); loadRows() }
function changeTab() { pageNum.value = 1; loadRows() }
function changePage(value: number) { pageNum.value = value; loadRows() }
refresh()
</script>

<style scoped>.toolbar { margin: 16px 0; }.section { margin-top: 20px; }.limit-form { margin-top: 20px; }.form-hint { margin-left: 12px; color: var(--el-text-color-secondary); }</style>
