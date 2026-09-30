<template>
  <div class="app-container">
    <el-alert title="Webhook 仅通知 Avatar 制作成功或最终失败。任务查询仍是状态依据；接收端按事件 ID 去重。" type="info" :closable="false" />
    <div class="toolbar"><el-button type="primary" @click="createOpen = true">创建 Endpoint</el-button><el-button @click="reload">刷新</el-button></div>
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="name" label="名称" min-width="145" /><el-table-column prop="url" label="HTTPS 地址" min-width="310" />
      <el-table-column prop="status" label="状态" width="105" /><el-table-column label="事件" min-width="185"><template #default="{ row }">{{ row.events.join('、') }}</template></el-table-column>
      <el-table-column label="操作" width="230"><template #default="{ row }">
        <el-button link type="primary" @click="openDeliveries(row)">投递记录</el-button>
        <el-button link type="primary" @click="rotate(row)">轮换密钥</el-button>
        <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination class="section" layout="prev, pager, next, total" :page-size="20" :total="endpointTotal" :current-page="endpointPage" @current-change="changeEndpointPage" />
    <el-dialog v-model="createOpen" title="创建 Webhook Endpoint" width="580px">
      <el-form label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="HTTPS 地址"><el-input v-model="form.url" placeholder="https://example.com/webhooks/avatar" /></el-form-item>
        <el-form-item label="事件"><el-checkbox-group v-model="form.events"><el-checkbox value="avatar.generation.succeeded">成功</el-checkbox><el-checkbox value="avatar.generation.failed">最终失败</el-checkbox></el-checkbox-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :disabled="!form.name.trim() || !form.url.trim() || !form.events.length" @click="create">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="secretOpen" title="立即保存签名密钥" width="600px" @closed="secret = ''">
      <el-alert title="完整密钥只显示一次。接收端须对原始请求体按 Standard Webhooks 验签。" type="warning" :closable="false" />
      <el-input :model-value="secret" readonly class="section" />
      <template #footer><el-button @click="copy">复制</el-button><el-button type="primary" @click="secretOpen = false">已保存</el-button></template>
    </el-dialog>
    <el-drawer v-model="deliveryOpen" :title="`${selected?.name || ''} · 投递记录`" size="750px">
      <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="起始 UTC 日" end-placeholder="结束 UTC 日" @change="changeRange" />
      <el-button @click="loadDeliveries">刷新</el-button>
      <el-table :data="deliveries" class="section">
        <el-table-column prop="eventId" label="事件 ID" min-width="225" /><el-table-column prop="taskId" label="任务 ID" min-width="135" />
        <el-table-column prop="status" label="状态" width="115" /><el-table-column prop="attemptCount" label="次数" width="70" />
        <el-table-column prop="lastHttpStatus" label="HTTP" width="75" /><el-table-column prop="lastErrorCode" label="错误码" min-width="150" /><el-table-column label="操作" width="75"><template #default="{ row }"><el-button link @click="openAttempts(row)">尝试</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="section" layout="prev, pager, next, total" :page-size="20" :total="deliveryTotal" :current-page="deliveryPage" @current-change="changeDeliveryPage" />
      <el-divider>逐次尝试</el-divider>
      <el-table :data="attempts"><el-table-column prop="attemptNo" label="序号" width="70" /><el-table-column prop="startedAt" label="开始" min-width="180" />
        <el-table-column prop="httpStatus" label="HTTP" width="80" /><el-table-column prop="errorCode" label="错误码" min-width="150" /><el-table-column prop="latencyMs" label="耗时 ms" width="90" /></el-table>
      <el-pagination class="section" layout="prev, pager, next, total" :page-size="20" :total="attemptTotal" :current-page="attemptPage" @current-change="changeAttemptPage" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { createWebhook, listAttempts, listDeliveries, listWebhooks, rotateWebhook, setWebhookStatus, type WebhookAttempt, type WebhookDelivery, type WebhookEndpoint } from '@/api/developer/webhook'
const items = ref<WebhookEndpoint[]>([]), loading = ref(false), createOpen = ref(false), secretOpen = ref(false)
const endpointPage = ref(1), endpointTotal = ref(0)
const secret = ref(''), deliveryOpen = ref(false), selected = ref<WebhookEndpoint>(), deliveries = ref<WebhookDelivery[]>([])
const attempts = ref<WebhookAttempt[]>([]), deliveryPage = ref(1), deliveryTotal = ref(0), attemptPage = ref(1), attemptTotal = ref(0)
const selectedDelivery = ref<WebhookDelivery>(), range = ref<[string, string]>()
const form = reactive({ name: '', url: '', events: ['avatar.generation.succeeded', 'avatar.generation.failed'] as string[] })
function reload() { loading.value = true; listWebhooks(endpointPage.value).then(result => { items.value = result.data?.items || []; endpointTotal.value = result.data?.total || 0 }).finally(() => { loading.value = false }) }
function changeEndpointPage(value: number) { endpointPage.value = value; reload() }
function show(result?: WebhookEndpoint) { reload(); if (result?.signingSecret) { secret.value = result.signingSecret; secretOpen.value = true } else ElMessage.warning('密钥无法再次显示；未保存时请轮换。') }
function create() { createWebhook({ name: form.name.trim(), url: form.url.trim(), events: form.events }).then(result => { createOpen.value = false; form.name = ''; form.url = ''; show(result.data) }) }
async function rotate(row: WebhookEndpoint) { try { await ElMessageBox.confirm('旧签名密钥将立即失效，接收端需同步更换。', '轮换签名密钥'); const result = await rotateWebhook(row.endpointId, row.revision); show(result.data) } catch { /* 用户取消或请求失败由请求拦截器提示 */ } }
async function toggle(row: WebhookEndpoint) { try { await ElMessageBox.confirm(`确认${row.status === 'ACTIVE' ? '停用' : '启用'}「${row.name}」？`, 'Webhook 状态'); await setWebhookStatus(row.endpointId, row.revision, row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'); reload() } catch { /* 用户取消或请求失败由请求拦截器提示 */ } }
function copy() { navigator.clipboard.writeText(secret.value).then(() => ElMessage.success('已复制')) }
function openDeliveries(row: WebhookEndpoint) { selected.value = row; deliveryPage.value = 1; selectedDelivery.value = undefined; attempts.value = []; deliveryOpen.value = true; loadDeliveries() }
function loadDeliveries() { if (!selected.value) return; listDeliveries(selected.value.endpointId, deliveryPage.value, range.value?.[0], range.value?.[1]).then(result => { deliveries.value = result.data?.items || []; deliveryTotal.value = result.data?.total || 0 }) }
function changeDeliveryPage(value: number) { deliveryPage.value = value; loadDeliveries() }
function changeRange() { deliveryPage.value = 1; attemptPage.value = 1; loadDeliveries(); if (selectedDelivery.value) loadAttempts() }
function openAttempts(row: WebhookDelivery) { selectedDelivery.value = row; attemptPage.value = 1; loadAttempts() }
function loadAttempts() { if (!selectedDelivery.value) return; listAttempts(selectedDelivery.value.deliveryId, attemptPage.value, range.value?.[0], range.value?.[1]).then(result => { attempts.value = result.data?.items || []; attemptTotal.value = result.data?.total || 0 }) }
function changeAttemptPage(value: number) { attemptPage.value = value; loadAttempts() }
reload()
</script>

<style scoped>.toolbar { margin: 16px 0; gap: 8px; }.section { margin-top: 16px; }</style>
