<template>
  <div class="app-container asset-manager">
    <section class="manager-hero"><p>ASSET CONTROL</p><h1>虚拟角色与声音资产</h1><span>这里管理已经制作完成并发布的虚拟角色和声音，不管理平台用户权限角色。</span></section>
    <el-alert :title="kind === 'avatars' ? '普通下架仅阻止新绑定；紧急停用会撤销平台活动会话。' : '声音下架不会替换已有配置；紧急停用会撤销平台活动会话。'" type="warning" :closable="false" class="mb16" />
    <el-tabs v-model="kind" class="asset-tabs" @tab-change="reload"><el-tab-pane label="虚拟角色" name="avatars" /><el-tab-pane label="官方声音" name="voices" /></el-tabs>
    <el-card shadow="never" class="asset-table"><el-table :data="rows" v-loading="loading" border>
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="status" label="状态" width="120"><template #default="{ row }"><el-tag :type="tagType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="revision" label="修订" width="100" />
      <el-table-column label="操作" min-width="360"><template #default="{ row }">
        <el-button link @click="showReferences(row)">引用</el-button>
        <el-button link :disabled="row.status !== 'PUBLISHED'" @click="change(row, 'unpublish')">普通下架</el-button>
        <el-button link type="warning" :disabled="!canDisable(row.status)" @click="change(row, 'disable')">紧急停用</el-button>
        <el-button link type="danger" :disabled="!canDelete(row.status)" @click="remove(row)">申请删除</el-button>
        <el-button link type="primary" :disabled="row.status !== 'DELETING'" @click="retry(row)">重试清理</el-button>
      </template></el-table-column>
    </el-table></el-card>
    <el-dialog v-model="referencesOpen" title="引用影响" width="620px">
      <el-descriptions v-if="references" :column="3" border><el-descriptions-item label="应用">{{ references.counts.applications }}</el-descriptions-item><el-descriptions-item label="会话">{{ references.counts.sessions }}</el-descriptions-item><el-descriptions-item label="生成">{{ references.counts.generations }}</el-descriptions-item></el-descriptions>
      <el-table v-if="references" :data="references.items" class="mt16" border><el-table-column prop="holderType" label="类型" /><el-table-column prop="holderId" label="ID" /><el-table-column prop="state" label="状态" /></el-table>
    </el-dialog>
  </div>
</template>
<script setup lang="ts" name="PublicAssets">
import { getReferences, unpublish, disable, requestDelete, retryCleanup, type PublicAssetKind, type PublicAssetReferences } from '@/api/asset/public-lifecycle'
import { listAdminPublicAvatars } from '@/api/asset/avatar'
import { listOfficialVoices } from '@/api/asset/official-voice'

type Row = { avatarId?: string; voiceId?: string; name: string; status: string; revision: string }
const { proxy } = getCurrentInstance(); const kind = ref<PublicAssetKind>('avatars'); const rows = ref<Row[]>([]); const loading = ref(false); const referencesOpen = ref(false); const references = ref<PublicAssetReferences>()
const idOf = (row: Row) => kind.value === 'avatars' ? row.avatarId! : row.voiceId!
const tagType = (status: string) => status === 'PUBLISHED' ? 'success' : status === 'DISABLED' ? 'danger' : status === 'DELETING' ? 'warning' : 'info'
const canDisable = (status: string) => ['DRAFT', 'PUBLISHED', 'UNLISTED'].includes(status); const canDelete = (status: string) => ['DRAFT', 'PUBLISHED', 'UNLISTED', 'DISABLED'].includes(status)
function reload() { loading.value = true; const source = kind.value === 'avatars' ? listAdminPublicAvatars(1, 100) : listOfficialVoices(); source.then((response: any) => { rows.value = response.data?.items || [] }).finally(() => { loading.value = false }) }
function showReferences(row: Row) { getReferences(kind.value, idOf(row)).then(response => { references.value = response.data; referencesOpen.value = true }) }
function change(row: Row, action: 'unpublish' | 'disable') { proxy?.$modal.prompt(action === 'disable' ? '确认紧急停用。请输入原因：' : '请输入下架原因：', '资产状态变更').then(({ value }: { value: string }) => { if (!value?.trim()) throw new Error('reason required'); return action === 'disable' ? disable(kind.value, idOf(row), row.revision, value.trim()) : unpublish(kind.value, idOf(row), row.revision, value.trim()) }).then(() => { proxy?.$modal.msgSuccess(action === 'disable' ? '已受理紧急停用。' : '已下架，既有引用不受影响。'); reload() }).catch(() => {}) }
function remove(row: Row) { getReferences(kind.value, idOf(row)).then(response => { const total = response.data?.total || 0; if (total > 0) { references.value = response.data; referencesOpen.value = true; return Promise.reject(new Error('in use')) } return proxy?.$modal.confirm('确认申请删除？系统会先清理未共享的实际文件，完成前保持删除中。') }).then(() => requestDelete(kind.value, idOf(row), row.revision)).then(() => { proxy?.$modal.msgSuccess('删除已受理。'); reload() }).catch(() => {}) }
function retry(row: Row) { retryCleanup(kind.value, idOf(row), row.revision).then(() => { proxy?.$modal.msgSuccess('已安排再次清理。'); reload() }) }
onMounted(reload)
</script>
<style scoped>
.asset-manager { max-width: 1320px; margin: 0 auto; }.manager-hero { margin: -20px -20px 24px; padding: 34px 36px; color: #edf6ff; background: linear-gradient(115deg, #102a43, #0b5c78 62%, #12a594); border-radius: 0 0 18px 18px; box-shadow: 0 16px 34px rgba(16, 42, 67, .18); }.manager-hero p { margin: 0 0 8px; color: #91e7dc; font-size: 12px; font-weight: 700; letter-spacing: .14em; }.manager-hero h1 { margin: 0 0 10px; font-family: "Microsoft YaHei", sans-serif; font-size: clamp(26px, 4vw, 36px); letter-spacing: -.04em; }.manager-hero span { color: #d5e8ef; line-height: 1.7; }.asset-tabs :deep(.el-tabs__item) { font-weight: 600; }.asset-table { border-radius: 12px; }.mb16 { margin-bottom: 16px; }.mt16 { margin-top: 16px; }
</style>
