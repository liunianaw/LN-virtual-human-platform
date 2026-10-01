<template>
  <div class="app-container">
    <el-alert title="生成一个动作 50 积分，音频合成每字符 0.01 积分；实际单价以请求预占时锁定的费率版本为准。资源存储暂时免费，容量限制仍生效。" type="info" :closable="false" />
    <div class="toolbar"><el-button @click="refresh">刷新</el-button></div>
    <el-card header="账号限额与余额" shadow="never">
      <el-descriptions :column="4" border>
        <el-descriptions-item label="并发 Session">{{ limits?.limits.maxSessions ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="并发制作">{{ limits?.limits.maxGenerationTasks ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="并发轮次">{{ limits?.limits.maxTurns ?? '未配置' }}</el-descriptions-item>
        <el-descriptions-item label="单文件字节">{{ limits?.limits.maxFileBytes ?? '未配置' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="limits?.balances || []" class="section">
        <el-table-column label="额度与单位" min-width="230"><template #default="{ row }">{{ quotaLabels[row.quotaType] || row.quotaType }}</template></el-table-column>
        <el-table-column prop="grantedUnits" label="总额度" /><el-table-column prop="usedUnits" label="已使用 / 占用" />
        <el-table-column prop="reservedUnits" label="待结算 / 预占" /><el-table-column prop="availableUnits" label="剩余可用" />
      </el-table>
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
        <el-table-column prop="unknownCount" label="待核对" width="90" /><el-table-column prop="knownUsageCount" label="有用量" width="90" />
        <el-table-column prop="inputTokens" label="输入 Token" width="110" /><el-table-column prop="outputTokens" label="输出 Token" width="110" />
        <el-table-column prop="inputChars" label="字符" width="90" /><el-table-column prop="imageCount" label="图片" width="90" />
      </el-table>
      <el-table v-else-if="tab === 'calls'" v-loading="loading" :data="calls">
        <el-table-column prop="callId" label="调用 ID" min-width="170" /><el-table-column prop="createdAt" label="创建时间" min-width="175" />
        <el-table-column prop="applicationId" label="应用" min-width="130" /><el-table-column prop="capability" label="能力" width="105" />
        <el-table-column prop="status" label="状态" width="105" /><el-table-column label="用量" min-width="145"><template #default="{ row }">{{ row.usageAvailable ? `${row.inputTokens ?? 0}/${row.outputTokens ?? 0} Token，${row.inputChars ?? 0} 字` : '不可获取' }}</template></el-table-column>
        <el-table-column label="积分消耗" min-width="120"><template #default="{ row }">{{ row.pointAmount == null ? '—' : `${row.pointAmount}（${row.pointState || '已锁价'}）` }}</template></el-table-column>
      </el-table>
      <el-table v-else v-loading="loading" :data="reservations">
        <el-table-column prop="reservationId" label="预占 ID" min-width="170" /><el-table-column label="额度与单位" min-width="230"><template #default="{ row }">{{ quotaLabels[row.quotaType] || row.quotaType }}</template></el-table-column>
        <el-table-column prop="businessType" label="业务" width="125" /><el-table-column prop="reservedUnits" label="预占" width="100" />
        <el-table-column label="锁价依据" min-width="180"><template #default="{ row }">{{ row.billingItem ? `${row.measuredUnits} × ${row.unitPrice} 积分` : '历史额度' }}</template></el-table-column>
        <el-table-column prop="state" label="状态" width="155" /><el-table-column prop="createdAt" label="创建时间" min-width="170" />
      </el-table>
      <el-pagination class="section" layout="prev, pager, next, total" :page-size="20" :total="total" :current-page="pageNum" @current-change="changePage" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { getCalls, getLimits, getReservations, getUsage, type CallRecord, type QuotaReservation, type UsageDaily, type UsageLimits } from '@/api/developer/usage'
const quotaLabels: Record<string, string> = { POINT: '积分', AVATAR_COUNT: '历史角色制作（次）', TTS_CHAR: '历史音频字符', STORAGE_BYTE: '资源存储（字节，暂时免费）' }
const capabilities = ['GENERATION', 'ASR', 'TTS', 'TOOL']
const statuses = ['STARTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED']
const limits = ref<UsageLimits>(), daily = ref<UsageDaily[]>([]), calls = ref<CallRecord[]>([]), reservations = ref<QuotaReservation[]>([])
const loading = ref(false), tab = ref('daily'), pageNum = ref(1), total = ref(0)
const range = ref<[string, string]>(), applicationId = ref(''), capability = ref(''), callStatus = ref('')
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
