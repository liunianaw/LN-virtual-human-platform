<template>
  <section v-loading="loading">
    <div class="voice-call-toolbar"><el-button @click="refresh">刷新状态与任务</el-button><span v-if="readiness">API：{{ readiness.apiReachable ? '可达' : '不可达' }} · 模型：{{ readiness.modelReady ? '就绪' : '未就绪' }}（不代表合成成功）</span><router-link to="/system/official-services">查看服务配置 ↗</router-link></div>
    <el-alert v-if="failed" type="error" title="语音调用数据加载失败，请重试。" :closable="false" />
    <el-table :data="readiness?.services || []"><el-table-column prop="name" label="服务" min-width="160" /><el-table-column prop="providerType" label="执行类型" min-width="150" /><el-table-column label="配置"><template #default="{ row }">{{ row.configValid ? '合法' : '无效' }}</template></el-table-column><el-table-column label="API"><template #default="{ row }">{{ row.apiReachable ? '可达' : '不可达' }}</template></el-table-column><el-table-column label="执行模型"><template #default="{ row }">{{ row.modelReady ? '就绪' : '未就绪' }}</template></el-table-column><el-table-column prop="lastSynthesisStatus" label="最近合成" min-width="140" /></el-table>
    <h2 class="voice-call-heading">语音逻辑任务与执行尝试</h2>
    <el-form inline><el-form-item label="任务 ID"><el-input v-model="filters.taskId" clearable inputmode="numeric" maxlength="19" @keyup.enter="search" /></el-form-item><el-form-item label="状态"><el-select v-model="filters.status" clearable placeholder="全部状态"><el-option v-for="status in statuses" :key="status" :label="status" :value="status" /></el-select></el-form-item><el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item></el-form>
    <el-table :data="tasks" empty-text="暂无语音调用"><el-table-column prop="taskId" label="逻辑任务" min-width="180" /><el-table-column prop="purpose" label="用途" min-width="120" /><el-table-column prop="status" label="状态" min-width="130" /><el-table-column prop="errorCode" label="原因" min-width="140" /><el-table-column prop="settlement" label="积分收尾" min-width="130" /><el-table-column label="降级" width="115"><template #default="{ row }">{{ row.degraded ? '已改变音色' : '否' }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" @click="selected = row">查看尝试</el-button></template></el-table-column></el-table>
    <pagination v-show="total > 0" :total="total" :page="pageNum" :limit="pageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="changePage" />
    <el-drawer :model-value="Boolean(selected)" title="执行尝试与异常" size="640px" @close="selected = undefined"><template v-if="selected"><el-descriptions :column="1"><el-descriptions-item label="逻辑任务">{{ selected.taskId }}</el-descriptions-item><el-descriptions-item label="状态">{{ selected.status }}</el-descriptions-item><el-descriptions-item label="事实投递">{{ selected.factDeliveryReview ? '需核对' : '正常或投递中' }}</el-descriptions-item></el-descriptions><el-table :data="selected.attempts"><el-table-column prop="providerType" label="执行类型" /><el-table-column prop="state" label="状态" /><el-table-column prop="costSource" label="费用依据" /><el-table-column prop="reasonCode" label="原因" /><el-table-column label="采用"><template #default="{ row }">{{ selected.winnerAttemptId === row.attemptId ? '已采用' : '未采用' }}</template></el-table-column></el-table><p class="voice-call-hint">费用来源不代表实际金额。未知费用应在调用事实中核对，不能按零处理。</p></template></el-drawer>
  </section>
</template>
<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { voiceReadiness, pageVoiceTasks, type VoiceTask, type VoiceReadiness } from '@/api/asset/official-voice'
const readiness = ref<VoiceReadiness>(), tasks = ref<VoiceTask[]>([]), selected = ref<VoiceTask>()
const pageNum = ref(1), pageSize = ref(20), total = ref(0), loading = ref(false), failed = ref(false)
const filters = reactive({ taskId: '', status: '' })
const statuses = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN']
let requestId = 0
async function loadTasks() {
  if (filters.taskId && !/^[1-9]\d*$/.test(filters.taskId)) { ElMessage.warning('请输入有效的任务 ID'); return }
  const request = ++requestId
  loading.value = true; failed.value = false
  try {
    const result = await pageVoiceTasks({ pageNum: pageNum.value, pageSize: pageSize.value, taskId: filters.taskId || undefined, status: filters.status || undefined })
    if (request === requestId) { tasks.value = result.data?.items || []; total.value = result.data?.total || 0 }
  } catch { if (request === requestId) failed.value = true }
  finally { if (request === requestId) loading.value = false }
}
function search() { pageNum.value = 1; loadTasks() }
function changePage({ page, limit }: { page: number; limit: number }) { pageNum.value = pageSize.value === limit ? page : 1; pageSize.value = limit; loadTasks() }
async function refresh() { await Promise.all([loadTasks(), voiceReadiness().then(result => { readiness.value = result.data }).catch(() => { failed.value = true })]) }
onMounted(refresh)
</script>
<style scoped>
.voice-call-toolbar { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; margin-bottom: 20px; font-size: 14px; color: var(--ln-muted); }.voice-call-toolbar a { color: var(--ln-accent); }.voice-call-heading { margin: 28px 0 18px; font-size: 14px; font-weight: 600; }.voice-call-filter { max-width: 320px; margin-bottom: 18px; }.voice-call-hint { font-size: 14px; line-height: 1.7; color: var(--ln-muted); }
</style>
