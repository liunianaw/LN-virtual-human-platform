<template>
  <div class="app-container">
    <el-card header="新用户注册赠送" shadow="never" class="mb8">
      <p>配置新用户默认并发额度、存储容量（MB）和积分。修改只对之后成功注册的用户生效，历史用户额度不变。</p>
      <el-button type="primary" plain v-hasPermi="['system:config:list', 'system:config:edit']"
        @click="router.push({ path: '/system/config', query: { configKey: 'platform.registration.' } })">修改注册赠送配置</el-button>
    </el-card>
    <el-card header="开发者使用总览" shadow="never">
      <el-form inline>
        <el-form-item label="统计日期"><el-date-picker v-model="usageRange" type="daterange" value-format="YYYY-MM-DD"
          range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" :disabled-date="disableFuture" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="overviewBusy" @click="refreshUsageRange">刷新统计</el-button></el-form-item>
        <el-form-item><span class="form-hint">按 UTC 日汇总，最多查询 31 天</span></el-form-item>
      </el-form>
      <el-row :gutter="12" class="metric-grid">
        <el-col v-for="item in overviewCards" :key="item.label" :xs="12" :sm="8" :md="6" :lg="3">
          <div class="metric"><div class="metric-label">{{ item.label }}</div><div class="metric-value">{{ item.value }}</div></div>
        </el-col>
      </el-row>
      <el-table :data="overview?.capabilities || []" border class="section" empty-text="该日期内暂无调用">
        <el-table-column prop="capability" label="能力" width="130"><template #default="{ row }">{{ capabilityLabel(row.capability) }}</template></el-table-column>
        <el-table-column prop="requestCount" label="调用数" /><el-table-column prop="successCount" label="成功" />
        <el-table-column prop="failureCount" label="失败" /><el-table-column prop="unknownCount" label="待核对" />
        <el-table-column prop="imageCount" label="生成图片" /><el-table-column prop="inputChars" label="语音字符" />
        <el-table-column prop="audioDurationMs" label="音频时长"><template #default="{ row }">{{ duration(row.audioDurationMs) }}</template></el-table-column>
      </el-table>
      <div class="list-toolbar">
        <el-input v-model.trim="accountQuery.keyword" clearable placeholder="搜索账号 ID、用户名、昵称或邮箱" @keyup.enter="searchAccounts" />
        <el-button :loading="overviewBusy" @click="searchAccounts">搜索用户</el-button>
      </div>
      <el-table :data="accountPage.items" border v-loading="overviewBusy" empty-text="暂无开发者用户">
        <el-table-column prop="userId" label="账号 ID" width="150" /><el-table-column prop="userName" label="用户名" min-width="130" />
        <el-table-column prop="nickName" label="昵称" min-width="130" />
        <el-table-column label="状态" width="85"><template #default="{ row }"><el-tag :type="row.status === '0' ? 'success' : 'info'">{{ row.status === '0' ? '正常' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column prop="grantedPoints" label="累计发放" min-width="105" /><el-table-column prop="usedPoints" label="已使用" min-width="90" />
        <el-table-column prop="reservedPoints" label="待结算" min-width="90" /><el-table-column prop="availablePoints" label="剩余积分" min-width="100" />
        <el-table-column prop="requestCount" label="期间调用" min-width="90" />
        <el-table-column prop="lastCallAt" label="最后调用" min-width="175"><template #default="{ row }">{{ row.lastCallAt || '-' }}</template></el-table-column>
        <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="selectAccount(row)">查看详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="accountQuery.pageNum" v-model:page-size="accountQuery.pageSize" class="pagination"
        layout="total, sizes, prev, pager, next" :total="accountPage.total" :page-sizes="[10, 20, 50, 100]"
        @current-change="loadAccounts" @size-change="searchAccounts" />
    </el-card>
    <el-card header="积分费率版本" shadow="never" class="section">
      <el-alert title="发布会生成不可覆盖的新版本。已经预占的请求继续按原费率结算，新请求按生效时间选择最新版本。" type="info" :closable="false" />
      <el-form inline class="section">
        <el-form-item label="每个动作"><el-input-number v-model="rateDraft.generationActionPoints" :min="0.01" :precision="2" :step="1" /></el-form-item>
        <el-form-item label="每个字符"><el-input-number v-model="rateDraft.ttsCharacterPoints" :min="0.01" :precision="2" :step="0.01" /></el-form-item>
        <el-form-item label="每字节存储"><el-input-number v-model="rateDraft.storageBytePoints" :min="0" :precision="2" :step="0.01" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="rateBusy" @click="publishRates">立即发布新费率</el-button></el-form-item>
      </el-form>
      <el-table :data="rateHistory" border>
        <el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="generationActionPoints" label="动作积分" />
        <el-table-column prop="ttsCharacterPoints" label="每字符积分" /><el-table-column prop="storageBytePoints" label="每字节积分" />
        <el-table-column prop="effectiveAt" label="UTC 生效时间" min-width="180" />
      </el-table>
    </el-card>
    <el-card :header="loadedAccountId ? `用户额度与使用详情 · ${loadedAccountId}` : '用户额度与使用详情'" shadow="never" class="section">
      <el-alert title="积分用于生成动作和音频合成；存储当前免费但容量限制仍生效。旧次数和字符额度仅用于历史请求收尾。" type="warning" :closable="false" />
      <el-form inline class="section"><el-form-item label="账号 ID"><el-input v-model.trim="adminAccountId" placeholder="输入管理员或开发者账号 ID" @keyup.enter="loadAdminQuotas" /></el-form-item><el-form-item><el-button :loading="adminBusy" @click="loadAdminQuotas">读取用户详情</el-button></el-form-item><el-form-item><el-button :loading="adminBusy" @click="selectCurrentAccount">读取当前账号</el-button></el-form-item></el-form>
      <p class="form-hint">管理员制作公共角色也使用当前账号的存储容量；这里的容量由平台分配，与云存储服务商的套餐余额分别管理。</p>
      <template v-if="loadedAccountId && adminUsage">
        <el-descriptions :column="4" border class="section">
          <el-descriptions-item label="用户名">{{ adminUsage.account.userName }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ adminUsage.account.nickName }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ adminUsage.account.email }}</el-descriptions-item>
          <el-descriptions-item label="账号状态">{{ adminUsage.account.status === '0' ? '正常' : '停用' }}</el-descriptions-item>
          <el-descriptions-item label="累计发放">{{ points(adminUsage.summary.grantedPoints) }}</el-descriptions-item>
          <el-descriptions-item label="已使用">{{ points(adminUsage.summary.usedPoints) }}</el-descriptions-item>
          <el-descriptions-item label="待结算">{{ points(adminUsage.summary.reservedPoints) }}</el-descriptions-item>
          <el-descriptions-item label="剩余积分">{{ points(adminUsage.summary.availablePoints) }}</el-descriptions-item>
          <el-descriptions-item label="期间调用">{{ adminUsage.summary.requestCount }}</el-descriptions-item>
          <el-descriptions-item label="成功 / 失败">{{ adminUsage.summary.successCount }} / {{ adminUsage.summary.failureCount }}</el-descriptions-item>
          <el-descriptions-item label="待核对">{{ adminUsage.summary.unknownCount }}</el-descriptions-item>
          <el-descriptions-item label="最后调用">{{ adminUsage.summary.lastCallAt || '-' }}</el-descriptions-item>
        </el-descriptions>
        <h3 class="subheading">限额配置</h3>
        <el-form label-width="140px" class="limit-form">
          <el-form-item label="并发 Session"><el-input-number v-model="adminLimits.maxSessions" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="并发制作"><el-input-number v-model="adminLimits.maxGenerationTasks" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="并发轮次"><el-input-number v-model="adminLimits.maxTurns" :min="0" :max="10000" /></el-form-item>
          <el-form-item label="单文件字节"><el-input-number v-model="adminLimits.maxFileBytes" :min="0" :max="Number.MAX_SAFE_INTEGER" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="adminBusy" @click="saveAdminLimits">保存限额</el-button><span class="form-hint">修订号 {{ adminLimits.revision }}，0 表示首次配置</span></el-form-item>
        </el-form>
        <h3 class="subheading">额度余额</h3>
        <el-table :data="adminQuota?.balances || []" border empty-text="该用户尚未发放积分或存储容量">
          <el-table-column prop="quotaType" label="额度类型"><template #default="{ row }">{{ quotaLabel(row.quotaType) }}</template></el-table-column><el-table-column prop="grantedUnits" label="累计授予" />
          <el-table-column prop="usedUnits" label="已使用" /><el-table-column prop="reservedUnits" label="待结算" />
          <el-table-column prop="availableUnits" label="可用" />
        </el-table>
        <h3 class="subheading">按能力统计</h3>
        <el-table :data="adminUsage.capabilities" border empty-text="该日期内暂无调用">
          <el-table-column prop="capability" label="能力"><template #default="{ row }">{{ capabilityLabel(row.capability) }}</template></el-table-column>
          <el-table-column prop="requestCount" label="调用数" /><el-table-column prop="successCount" label="成功" />
          <el-table-column prop="failureCount" label="失败" /><el-table-column prop="unknownCount" label="待核对" />
          <el-table-column prop="imageCount" label="图片" /><el-table-column prop="inputChars" label="语音字符" />
        </el-table>
        <el-collapse class="section">
          <el-collapse-item title="最近 20 条调用" name="calls">
            <el-table :data="adminUsage.recentCalls" border>
              <el-table-column prop="createdAt" label="时间" min-width="175" /><el-table-column prop="capability" label="能力" width="110"><template #default="{ row }">{{ capabilityLabel(row.capability) }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="110" /><el-table-column prop="pointAmount" label="积分" width="100"><template #default="{ row }">{{ row.pointAmount ?? '-' }}</template></el-table-column>
              <el-table-column prop="inputChars" label="字符" width="90" /><el-table-column prop="imageCount" label="图片" width="80" /><el-table-column prop="errorCode" label="错误码" min-width="140" />
            </el-table>
          </el-collapse-item>
          <el-collapse-item title="最近 20 条额度预占" name="reservations">
            <el-table :data="adminUsage.recentReservations" border>
              <el-table-column prop="createdAt" label="时间" min-width="175" /><el-table-column prop="businessType" label="业务" width="130" />
              <el-table-column prop="billingItem" label="计费项" min-width="140" /><el-table-column prop="measuredUnits" label="计量" width="90" />
              <el-table-column prop="unitPrice" label="单价" width="90" /><el-table-column prop="reservedUnits" label="预占" width="90" />
              <el-table-column prop="settledUnits" label="结算" width="90" /><el-table-column prop="state" label="状态" width="140" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <h3 class="subheading">积分与容量发放</h3>
        <el-form inline class="section">
          <el-form-item label="增加积分"><el-input-number v-model="pointGrant.points" :min="0.01" :precision="2" :step="10" @change="pointGrantKey = ''" /></el-form-item>
          <el-form-item label="授予依据"><el-input v-model.trim="pointGrant.reason" maxlength="500" placeholder="填写运营依据" @input="pointGrantKey = ''" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="adminBusy" @click="submitPointGrant">授予积分</el-button></el-form-item>
        </el-form>
        <el-form inline class="section">
          <el-form-item label="容量类型"><el-select v-model="grant.quotaType" style="width: 175px" @change="grantKey = ''"><el-option label="存储字节" value="STORAGE_BYTE" /></el-select></el-form-item>
          <el-form-item label="增加字节"><el-input-number v-model="grant.units" :min="1" :max="1000000000000" @change="grantKey = ''" /></el-form-item>
          <el-form-item label="授予依据"><el-input v-model.trim="grant.reason" maxlength="500" placeholder="填写测试或运营依据" @input="grantKey = ''" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="adminBusy" @click="submitGrant">授予额度</el-button></el-form-item>
          <el-form-item><span class="form-hint">1 GiB = 1,073,741,824 字节</span></el-form-item>
        </el-form>
      </template>
    </el-card>
  </div>
</template>
<script setup lang="ts" name="AccountQuotaGovernance">
import { getAdminAccountUsage, getAdminUsageAccounts, getAdminUsageOverview, getPointRates, grantAccountPoints,
  grantAccountQuota, publishPointRate, saveAccountLimits, type AccountLimitInput, type AdminAccountUsageDetail,
  type AdminAccountUsageRow, type AdminUsageOverview, type Page, type PointRate, type PointRateInput,
  type QuotaGrantInput, type UsageLimits } from '@/api/developer/usage'
import { ElMessage, ElMessageBox } from 'element-plus'
import useUserStore from '@/store/modules/user'

const userStore = useUserStore()
const router = useRouter()

function utcDate(days: number) { const value = new Date(); value.setUTCDate(value.getUTCDate() + days); return value.toISOString().slice(0, 10) }
const usageRange = ref<[string, string]>([utcDate(-6), utcDate(0)])
const overview = ref<AdminUsageOverview>(), overviewBusy = ref(false)
const accountPage = reactive<Page<AdminAccountUsageRow>>({ items: [], total: 0, pageNum: 1, pageSize: 20 })
const accountQuery = reactive({ keyword: '', pageNum: 1, pageSize: 20 })
const adminAccountId = ref(''), loadedAccountId = ref(''), adminQuota = ref<UsageLimits>()
const adminUsage = ref<AdminAccountUsageDetail>(), adminBusy = ref(false)
const adminLimits = reactive<AccountLimitInput>({ revision: 0, maxFileBytes: 0, maxSessions: 0, maxGenerationTasks: 0, maxTurns: 0 })
const grant = reactive<QuotaGrantInput>({ quotaType: 'STORAGE_BYTE', units: 1, reason: '' })
const grantKey = ref('')
const pointGrant = reactive({ points: 100, reason: '' }), pointGrantKey = ref('')
const rateBusy = ref(false), rateHistory = ref<PointRate[]>([])
const rateDraft = reactive<PointRateInput>({ generationActionPoints: 50, ttsCharacterPoints: 0.01, storageBytePoints: 0 })

const overviewCards = computed(() => [
  { label: '全部用户', value: overview.value?.totalUsers ?? 0 }, { label: '正常用户', value: overview.value?.activeUsers ?? 0 },
  { label: '期间使用用户', value: overview.value?.usageUsers ?? 0 }, { label: '期间调用', value: overview.value?.requestCount ?? 0 },
  { label: '累计发放积分', value: points(overview.value?.grantedPoints) }, { label: '累计使用积分', value: points(overview.value?.usedPoints) },
  { label: '待结算积分', value: points(overview.value?.reservedPoints) }, { label: '平台剩余积分', value: points(overview.value?.availablePoints) }
])
function rangeParams() { return { from: usageRange.value[0], to: usageRange.value[1] } }
function disableFuture(date: Date) { return date.getTime() > Date.now() }
function points(value?: number) { return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }
function duration(value?: number) { return `${((value || 0) / 1000).toFixed(1)} 秒` }
function capabilityLabel(value: string) { return ({ GENERATION: '角色生成', ASR: '语音识别', TTS: '音频合成', TOOL: '工具', LLM: '大模型', CONTEXT: '页面上下文' } as Record<string, string>)[value] || value }
function quotaLabel(value: string) { return value === 'POINT' ? '积分' : value === 'STORAGE_BYTE' ? '存储字节' : value }
function accountId() {
  if (!/^[1-9]\d*$/.test(adminAccountId.value)) { ElMessage.warning('请输入有效的账号 ID'); return '' }
  return adminAccountId.value
}
async function loadOverview() { const result = await getAdminUsageOverview(rangeParams()); overview.value = result.data }
async function loadAccounts() {
  const result = await getAdminUsageAccounts({ keyword: accountQuery.keyword || undefined, ...rangeParams(), pageNum: accountQuery.pageNum, pageSize: accountQuery.pageSize })
  Object.assign(accountPage, result.data || { items: [], total: 0, pageNum: accountQuery.pageNum, pageSize: accountQuery.pageSize })
}
async function loadPlatformUsage() { overviewBusy.value = true; try { await Promise.all([loadOverview(), loadAccounts()]) } finally { overviewBusy.value = false } }
async function refreshUsageRange() { accountQuery.pageNum = 1; await loadPlatformUsage(); if (loadedAccountId.value) await loadAdminQuotas() }
async function searchAccounts() { accountQuery.pageNum = 1; await loadAccounts() }
async function selectAccount(row: AdminAccountUsageRow) { adminAccountId.value = row.userId; await loadAdminQuotas() }
async function selectCurrentAccount() { adminAccountId.value = String(userStore.id); await loadAdminQuotas() }
async function loadAdminQuotas() {
  const id = accountId()
  if (!id) return
  adminBusy.value = true
  try {
    const result = await getAdminAccountUsage(id, rangeParams())
    adminUsage.value = result.data
    adminQuota.value = result.data?.quota
    loadedAccountId.value = id
    const limits = result.data?.quota?.limits || {}
    Object.assign(adminLimits, { revision: limits.revision ?? 0, maxFileBytes: limits.maxFileBytes ?? 0,
      maxSessions: limits.maxSessions ?? 0, maxGenerationTasks: limits.maxGenerationTasks ?? 0, maxTurns: limits.maxTurns ?? 0 })
  } finally { adminBusy.value = false }
}
async function saveAdminLimits() {
  if (!loadedAccountId.value || loadedAccountId.value !== accountId()) return
  adminBusy.value = true
  try {
    await saveAccountLimits(loadedAccountId.value, { ...adminLimits })
    await loadAdminQuotas()
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
    grantKey.value = ''
    grant.reason = ''
    await Promise.all([loadAdminQuotas(), loadPlatformUsage()])
    ElMessage.success(result.data?.applied ? '容量已授予' : '该请求已处理，无重复授予')
  } finally { adminBusy.value = false }
}
async function submitPointGrant() {
  if (!loadedAccountId.value || loadedAccountId.value !== accountId()) return
  if (!pointGrant.reason.trim()) { ElMessage.warning('请填写授予依据'); return }
  if (!pointGrantKey.value) pointGrantKey.value = crypto.randomUUID()
  adminBusy.value = true
  try {
    const result = await grantAccountPoints(loadedAccountId.value, pointGrant.points, pointGrant.reason, pointGrantKey.value)
    pointGrantKey.value = ''; pointGrant.reason = ''
    await Promise.all([loadAdminQuotas(), loadPlatformUsage()])
    ElMessage.success(result.data?.applied ? '积分已授予' : '该请求已处理，无重复授予')
  } finally { adminBusy.value = false }
}
async function loadPointRates() {
  const result = await getPointRates(); rateHistory.value = result.data?.items || []
  const current = result.data?.current
  if (current) Object.assign(rateDraft, { generationActionPoints: current.generationActionPoints,
    ttsCharacterPoints: current.ttsCharacterPoints, storageBytePoints: current.storageBytePoints })
}
async function publishRates() {
  await ElMessageBox.confirm('发布后旧请求仍按旧价结算，新请求立即使用这组费率。确认发布？', '发布积分费率', { type: 'warning' })
  rateBusy.value = true
  try { await publishPointRate({ ...rateDraft }); await loadPointRates(); ElMessage.success('新费率已发布') }
  finally { rateBusy.value = false }
}
Promise.all([loadPointRates(), loadPlatformUsage()])
</script>
<style scoped>
.section { margin-top: 20px; }
.metric-grid { margin-top: 6px; }
.metric { min-height: 92px; padding: 18px; margin-top: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; background: var(--el-fill-color-extra-light); }
.metric-label { color: var(--el-text-color-secondary); font-size: 14px; }
.metric-value { margin-top: 10px; color: var(--el-text-color-primary); font-size: 24px; font-weight: 600; }
.list-toolbar { display: flex; gap: 10px; max-width: 620px; margin: 24px 0 12px; }
.pagination { justify-content: flex-end; margin-top: 16px; }
.limit-form { margin-top: 16px; }
.form-hint { margin-left: 12px; color: var(--el-text-color-secondary); }
.subheading { margin: 24px 0 12px; font-size: 15px; font-weight: 600; }
@media (max-width: 768px) { .list-toolbar { max-width: none; } .pagination { justify-content: flex-start; overflow-x: auto; } }
</style>
