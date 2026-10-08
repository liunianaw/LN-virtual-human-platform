<template>
  <div class="app-container">
    <header class="console-page-heading"><div><h1>Skills</h1><p>配置提示词与工具，供应用按需选择。</p></div></header>
    <el-alert :title="isAdmin ? '这里只维护公共 Skills；不能查看或编辑开发者私有 Skill。' : '这里只维护本账号私有 Skills；公共 Skill 可在 Application 中只读选择。'" type="info" :closable="false" />
    <div class="toolbar"><el-button type="primary" @click="startCreate">创建{{ isAdmin ? '公共' : '私有' }} Skill</el-button><el-button @click="reload">刷新</el-button></div>
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="skillType" label="类型" width="130" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.skillId)">编辑</el-button></template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" :page="pageNum" :limit="pageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="changePage" />

    <el-drawer v-model="editorOpen" :title="detail ? '编辑当前配置' : '创建 Skill'" size="720px" @closed="form.accessToken = ''">
      <el-form label-width="125px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" maxlength="1000" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.skillType" class="full"><el-option label="Prompt" value="PROMPT" /><el-option label="HTTP Tool" value="HTTP_TOOL" /></el-select></el-form-item>
        <el-form-item label="采集需求"><el-checkbox v-model="form.contextRequirements.page">Page</el-checkbox><el-checkbox v-model="form.contextRequirements.hybrid">Hybrid</el-checkbox></el-form-item>
        <template v-if="form.skillType === 'PROMPT'">
          <el-form-item label="指令内容"><el-input v-model="form.instructions" type="textarea" :rows="8" maxlength="32768" /></el-form-item>
          <el-form-item label="导入 JSON"><el-input v-model="importText" type="textarea" :rows="3" placeholder='{"instructions":"..."}' /><el-button @click="importPrompt">解析</el-button></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="工具名"><el-input v-model="form.toolName" placeholder="lookupOrder" /></el-form-item>
          <el-form-item label="固定 HTTPS URL"><el-input v-model="form.toolUrl" /></el-form-item>
          <el-form-item label="方法"><el-select v-model="form.httpMethod"><el-option value="GET" /><el-option value="POST" /></el-select></el-form-item>
          <el-form-item label="Tool Token"><el-input v-model="form.accessToken" type="password" show-password autocomplete="new-password" :placeholder="detail?.tokenSuffix ? '留空保留当前 Token；填写则替换' : '可留空；保存后不回显'" /></el-form-item>
          <el-form-item label="输入 Schema"><el-input v-model="inputSchemaText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="输出 Schema"><el-input v-model="outputSchemaText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="前端返回字段"><el-input v-model="frontendFieldsText" placeholder="逗号分隔；留空则不返回" /></el-form-item>
          <el-form-item label="身份绑定"><el-checkbox v-model="bindExternalUser">绑定 externalUserId 到请求头</el-checkbox></el-form-item>
          <el-form-item label="超时 / 结果上限"><el-input-number v-model="form.timeoutMs" :min="1000" :max="30000" /><el-input-number v-model="form.maxResultBytes" :min="1" :max="1048576" /></el-form-item>
          <el-form-item label="每 Session 次数"><el-input-number v-model="form.maxCallsPerSession" :min="1" :max="100" /></el-form-item>
        </template>
        <el-form-item><el-button type="primary" :disabled="!canSave" @click="save">保存当前配置</el-button></el-form-item>
      </el-form>
      <template v-if="detail">
        <el-divider />
        <div class="toolbar">
          <el-button v-if="detail.status === 'UNLISTED'" @click="status('PUBLISHED')">上架</el-button>
          <el-button v-if="detail.status === 'PUBLISHED'" @click="status('UNLISTED')">下架</el-button>
          <el-button v-if="detail.status !== 'DISABLED'" type="warning" @click="status('DISABLED')">停用</el-button>
          <el-button v-if="detail.status === 'DISABLED'" @click="status('PUBLISHED')">重新启用</el-button>
          <el-button v-if="detail.skillType === 'HTTP_TOOL'" @click="check">检查 TLS</el-button>
          <el-button type="danger" :disabled="detail.referenceCount > 0" @click="remove">删除</el-button>
        </div>
        <p>修订 {{ detail.revision }} · 引用 {{ detail.referenceCount }}</p>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeSkillStatus, checkSkillConnection, createSkill, deleteSkill, getSkill, listSkills, updateSkill, type SkillDetail, type SkillInput, type SkillSummary } from '@/api/developer/skill'
import useUserStore from '@/store/modules/user'

const isAdmin = computed(() => useUserStore().roles.includes('admin'))
const blank = (): SkillInput => ({ name: '', description: '', skillType: 'PROMPT', instructions: '', contextRequirements: {}, httpMethod: 'GET', timeoutMs: 5000, maxResultBytes: 65536, maxCallsPerSession: 10 })
const pageNum = ref(1), pageSize = ref(20), total = ref(0)
const loading = ref(false), editorOpen = ref(false), items = ref<SkillSummary[]>([]), detail = ref<SkillDetail>()
const form = reactive<SkillInput>(blank()), importText = ref('')
const inputSchemaText = ref('{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}')
const outputSchemaText = ref('{"type":"object","properties":{"summary":{"type":"string"}}}')
const frontendFieldsText = ref(''), bindExternalUser = ref(true)
const canSave = computed(() => !!form.name.trim() && (form.skillType === 'PROMPT' ? !!form.instructions?.trim() : !!form.toolName?.trim() && !!form.toolUrl?.trim()))
let listRequest = 0
function reload() { const request = ++listRequest; loading.value = true; listSkills(isAdmin.value, { pageNum: pageNum.value, pageSize: pageSize.value }).then(res => { if (request !== listRequest) return; items.value = res.data?.items || []; total.value = res.data?.total || 0 }).finally(() => { if (request === listRequest) loading.value = false }) }
function changePage({ page, limit }: { page: number; limit: number }) { pageNum.value = pageSize.value === limit ? page : 1; pageSize.value = limit; reload() }
function reset(value?: SkillDetail) { Object.keys(form).forEach(key => Reflect.deleteProperty(form, key)); Object.assign(form, blank(), value || {}, { accessToken: '' }); inputSchemaText.value = value?.inputSchema ? JSON.stringify(value.inputSchema) : '{"type":"object"}'; outputSchemaText.value = value?.outputSchema ? JSON.stringify(value.outputSchema) : '{"type":"object"}'; frontendFieldsText.value = value?.frontendFields?.join(', ') || ''; bindExternalUser.value = value?.identityBinding?.externalUserId === 'HEADER' }
function startCreate() { detail.value = undefined; reset(); editorOpen.value = true }
function openDetail(id: string) { getSkill(id, isAdmin.value).then(res => { if (!res.data) return; detail.value = res.data; reset(res.data); editorOpen.value = true }) }
function importPrompt() { try { const value = JSON.parse(importText.value); if (typeof value.instructions !== 'string' || !value.instructions.trim()) throw Error(); form.instructions = value.instructions; form.contextRequirements = value.contextRequirements || {}; form.importFormat = 'JSON' } catch { ElMessage.error('JSON 须包含 instructions 字符串。') } }
function payload(): SkillInput {
  const common = { name: form.name.trim(), description: form.description?.trim() || undefined, skillType: form.skillType, contextRequirements: form.contextRequirements, importFormat: form.importFormat }
  if (form.skillType === 'PROMPT') return { ...common, skillType: 'PROMPT', instructions: form.instructions }
  return { ...common, skillType: 'HTTP_TOOL', toolName: form.toolName, toolUrl: form.toolUrl, httpMethod: form.httpMethod,
    inputSchema: JSON.parse(inputSchemaText.value), outputSchema: JSON.parse(outputSchemaText.value), accessToken: form.accessToken || undefined,
    requiresUserCredential: false, identityBinding: bindExternalUser.value ? { externalUserId: 'HEADER' } : {},
    frontendFields: frontendFieldsText.value.split(',').map((x: string) => x.trim()).filter(Boolean), timeoutMs: form.timeoutMs,
    maxResultBytes: form.maxResultBytes, maxCallsPerSession: form.maxCallsPerSession }
}
function save() { let data: SkillInput; try { data = payload() } catch { ElMessage.error('Schema 必须是有效 JSON。'); return }
  const action = detail.value ? updateSkill(detail.value.skillId, detail.value.revision, data, isAdmin.value) : createSkill(data, isAdmin.value)
  action.then(res => { form.accessToken = ''; ElMessage.success('当前配置已保存。'); if (res.data) openDetail(res.data.skillId); reload() }) }
function status(next: SkillSummary['status']) { if (!detail.value) return; const row = detail.value; ElMessageBox.prompt('请输入操作原因。', 'Skill 状态').then(({ value }: { value: string }) => changeSkillStatus(row.skillId, row.revision, next, value, isAdmin.value).then(res => { if (res.data) openDetail(res.data.skillId); reload() })) }
function remove() { if (!detail.value) return; const row = detail.value; ElMessageBox.confirm('删除后不可恢复。', '删除 Skill').then(() => deleteSkill(row.skillId, row.revision, isAdmin.value).then(() => { editorOpen.value = false; detail.value = undefined; reload() })) }
function check() { if (!detail.value) return; checkSkillConnection(detail.value.skillId, isAdmin.value).then(res => res.data?.success ? ElMessage.success('公网地址与 TLS 验证通过。') : ElMessage.error(`连接失败：${res.data?.errorCode || 'UNKNOWN'}`)) }
reload()
</script>

<style scoped>.toolbar{display:flex;gap:8px;margin:16px 0}.full{width:100%}</style>
