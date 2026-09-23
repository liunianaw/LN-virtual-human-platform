<template>
  <div class="app-container">
    <el-alert title="这里列出你的角色资产，而不是最近的制作任务；采用只会发布应用的新配置，不会重新制作。" type="info" :closable="false" />
    <el-row class="toolbar"><el-input v-model="keyword" placeholder="搜索角色名称" clearable @keyup.enter="load" /><el-select v-model="status" clearable placeholder="状态"><el-option v-for="item in statuses" :key="item" :value="item" /></el-select><el-button type="primary" @click="load">查询</el-button><el-button @click="goProduction">去制作角色</el-button></el-row>
    <el-table v-loading="loading" :data="items"><el-table-column prop="name" label="角色" min-width="180" /><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="versionCount" label="版本" width="80" /><el-table-column label="操作" width="280"><template #default="{ row }"><el-button link type="primary" @click="detail(row)">详情</el-button><el-button link :disabled="!row.currentVersionId || row.status !== 'PUBLISHED'" @click="adopt(row)">采用当前版本</el-button><el-button link type="danger" :disabled="row.status === 'DELETING'" @click="remove(row)">删除</el-button></template></el-table-column></el-table>
    <el-drawer v-model="open" title="我的角色" size="560px"><template v-if="selected"><p>状态：{{ selected.status }} · 修订：{{ selected.revision }}</p><p>引用：应用 {{ references.counts.applications }}，会话 {{ references.counts.sessions }}，制作 {{ references.counts.generations }}</p><el-alert v-if="references.counts.applications || references.counts.sessions || references.counts.generations" title="存在有效引用，无法删除。请先关闭调试会话或修改对应应用。" type="warning" :closable="false" /><el-table :data="selected.versions"><el-table-column prop="versionNo" label="版本" width="80" /><el-table-column prop="status" label="状态" /><el-table-column label="操作"><template #default="{ row }"><el-button link :disabled="row.status !== 'PUBLISHED'" @click="adoptVersion(row.versionId)">采用此版本</el-button></template></el-table-column></el-table><el-divider v-if="references.applications.length">引用应用</el-divider><el-tag v-for="app in references.applications" :key="app.applicationId" class="app">{{ app.name }} · {{ app.status }}</el-tag></template></el-drawer>
  </div>
</template>
<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { deleteOwnedAvatar, getAvatarDetail, getAvatarReferences, listOwnedAvatars, type AvatarCatalogItem, type AvatarDetail, type AvatarReferences } from '@/api/asset/avatar'
import { listApplications, type ApplicationSummary } from '@/api/application'

const router = useRouter(); const loading = ref(false); const open = ref(false); const keyword = ref(''); const status = ref(''); const statuses = ['DRAFT', 'PUBLISHED', 'UNLISTED', 'DISABLED', 'DELETING']
const items = ref<AvatarCatalogItem[]>([]); const selected = ref<AvatarDetail>(); const references = reactive<AvatarReferences>({ counts: { applications: 0, sessions: 0, generations: 0 }, applications: [] })
function load() { loading.value = true; listOwnedAvatars(1, 20, status.value || undefined, keyword.value.trim() || undefined).then(res => { items.value = res.data?.items || [] }).finally(() => { loading.value = false }) }
function detail(row: AvatarCatalogItem) { Promise.all([getAvatarDetail(row.avatarId), getAvatarReferences(row.avatarId)]).then(([avatar, refs]) => { selected.value = avatar.data; Object.assign(references, refs.data || { counts: { applications: 0, sessions: 0, generations: 0 }, applications: [] }); open.value = true }) }
async function adopt(row: AvatarCatalogItem) { if (row.currentVersionId) await chooseApplication(row.currentVersionId) }
async function adoptVersion(versionId: string) { await chooseApplication(versionId) }
async function chooseApplication(avatarVersionId: string) { const result = await listApplications(); const apps = (result.data?.items || []).filter((app: ApplicationSummary) => app.status === 'ACTIVE'); if (!apps.length) { ElMessage.warning('请先创建并启用一个应用。'); return } const names = apps.map((app: ApplicationSummary) => `${app.applicationId}:${app.name}`).join('\n'); const answer = await ElMessageBox.prompt(`选择应用 ID：\n${names}`, '采用角色版本', { inputPattern: /^\d+$/, inputErrorMessage: '请输入上方的应用 ID' }); const app = apps.find((item: ApplicationSummary) => item.applicationId === answer.value); if (!app) { ElMessage.error('只能选择自己的启用应用。'); return } router.push({ path: '/system/applications', query: { applicationId: app.applicationId, avatarVersionId } }) }
function remove(row: AvatarCatalogItem) { ElMessageBox.confirm('删除申请会阻止新绑定；有引用时会被拒绝。确认继续？', '删除角色', { type: 'warning' }).then(() => deleteOwnedAvatar(row.avatarId, String(row.revision)).then(() => { ElMessage.success('已提交删除申请，清理完成前会显示删除中。'); open.value = false; load() })) }
function goProduction() { router.push('/system/avatar') }
load()
</script>
<style scoped>.toolbar { display:flex; gap:8px; margin:16px 0; }.toolbar .el-input { width:260px; }.app { margin-right:8px; }</style>
