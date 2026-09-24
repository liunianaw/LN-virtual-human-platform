<template>
  <div class="app-container">
    <el-alert title="管理 Key 仅供可信后端使用。创建或轮换后的完整值只显示一次。" type="warning" :closable="false" />
    <el-row class="toolbar"><el-button type="primary" @click="createOpen = true">创建管理 Key</el-button><el-button @click="reload">刷新</el-button></el-row>
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="name" label="名称" min-width="150" /><el-table-column prop="publicId" label="标识" min-width="220" />
      <el-table-column prop="displaySuffix" label="末尾" width="100" /><el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="lastUsedAt" label="最近使用" min-width="170" />
      <el-table-column label="操作" width="210"><template #default="{ row }">
        <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="rotate(row)">轮换</el-button>
        <el-button link type="warning" :disabled="row.status !== 'ACTIVE'" @click="change(row, 'disable')">停用</el-button>
        <el-button link type="danger" :disabled="row.status === 'DELETED'" @click="change(row, 'delete')">删除</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="createOpen" title="创建管理 Key" width="520px"><el-form label-width="70px">
      <el-form-item label="名称"><el-input v-model="name" maxlength="100" /></el-form-item>
      <el-form-item label="权限"><el-checkbox-group v-model="scopes"><el-checkbox v-for="scope in options" :key="scope" :value="scope">{{ scope }}</el-checkbox></el-checkbox-group></el-form-item>
    </el-form><template #footer><el-button @click="createOpen = false">取消</el-button><el-button type="primary" :disabled="!name.trim() || !scopes.length" @click="create">创建</el-button></template></el-dialog>
    <el-dialog v-model="revealOpen" title="立即保存新 Key" width="580px" @closed="secret = ''"><el-alert title="完整值关闭后无法再次查看；响应丢失时请重新轮换。" type="warning" :closable="false" /><el-input :model-value="secret" readonly class="secret" /><template #footer><el-button @click="copy">复制</el-button><el-button type="primary" @click="revealOpen = false">已保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeManagementKey, createManagementKey, listManagementKeys, rotateManagementKey, type AccessKeySummary, type IssuedAccessKey } from '@/api/developer/access-key'

const options = ['assets:read', 'assets:write', 'generation:read', 'generation:write', 'config:read', 'config:write', 'keys:write', 'usage:read', 'webhooks:write']
const loading = ref(false), createOpen = ref(false), revealOpen = ref(false)
const items = ref<AccessKeySummary[]>([]), name = ref(''), scopes = ref<string[]>([]), secret = ref('')
function reload() { loading.value = true; listManagementKeys().then(res => { items.value = res.data || [] }).finally(() => { loading.value = false }) }
function show(result: IssuedAccessKey) { reload(); if (result.secret) { secret.value = result.secret; revealOpen.value = true } else ElMessage.warning('操作已完成，但本次无法再次显示完整 Key；如未保存请重新轮换。') }
function create() { createManagementKey(name.value.trim(), scopes.value).then(res => { createOpen.value = false; name.value = ''; scopes.value = []; show(res.data!) }) }
function rotate(row: AccessKeySummary) { ElMessageBox.confirm(`轮换「${row.name}」后，旧 Key 的新请求立即失效。`, '轮换管理 Key').then(() => rotateManagementKey(row.keyId).then(res => show(res.data!))) }
function change(row: AccessKeySummary, action: 'disable' | 'delete') { ElMessageBox.confirm(`确认${action === 'disable' ? '停用' : '删除'}「${row.name}」？`, '管理 Key').then(() => changeManagementKey(row.keyId, action).then(() => reload())) }
function copy() { navigator.clipboard.writeText(secret.value).then(() => ElMessage.success('已复制')) }
reload()
</script>

<style scoped>.toolbar { margin: 16px 0; gap: 8px; }.secret { margin-top: 16px; }</style>
