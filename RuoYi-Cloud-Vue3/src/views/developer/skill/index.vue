<template>
  <div class="app-container">
    <el-alert title="Tool 仅连接你信任的查询端点。GET/POST 和 Schema 无法证明远端无写入副作用；端点须自行校验业务用户权限。" type="info" :closable="false" />
    <div class="toolbar"><el-button type="primary" @click="startCreate(false)">创建私有 Skill</el-button><el-button v-if="isAdmin" @click="startCreate(true)">创建官方 Skill</el-button><el-button @click="reload">刷新</el-button></div>
    <el-table v-loading="loading" :data="items">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="visibility" label="来源" width="110" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row.skillId)">查看</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="editorOpen" :title="editing ? '发布 Skill 新版本' : official ? '创建官方 Skill' : '创建私有 Skill'" width="760px" @closed="form.accessToken = ''">
      <el-form label-width="125px">
        <template v-if="!editing"><el-form-item label="名称"><el-input v-model="name" maxlength="100" /></el-form-item><el-form-item label="说明"><el-input v-model="description" maxlength="1000" /></el-form-item></template>
        <el-form-item label="类型"><el-select v-model="form.skillType" class="full"><el-option label="Prompt" value="PROMPT" /><el-option label="HTTP Tool" value="HTTP_TOOL" /></el-select></el-form-item>
        <el-form-item label="Context 需求"><el-checkbox v-model="form.contextRequirements.element">Element</el-checkbox><el-checkbox v-model="form.contextRequirements.page">Page</el-checkbox><el-checkbox v-model="form.contextRequirements.hybrid">Hybrid</el-checkbox></el-form-item>
        <template v-if="form.skillType === 'PROMPT'">
          <el-form-item label="指令内容"><el-input v-model="form.instructions" type="textarea" :rows="8" maxlength="32768" /></el-form-item>
          <el-form-item label="导入 JSON"><el-input v-model="importText" type="textarea" :rows="3" placeholder='{"instructions":"...","contextRequirements":{"page":true}}' /><el-button @click="importPrompt">解析配置</el-button></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="工具名"><el-input v-model="form.toolName" placeholder="lookupOrder" /></el-form-item>
          <el-form-item label="固定 HTTPS URL"><el-input v-model="form.toolUrl" placeholder="https://api.example.com/lookup" /></el-form-item>
          <el-form-item label="方法"><el-select v-model="form.httpMethod"><el-option value="GET" /><el-option value="POST" /></el-select></el-form-item>
          <el-form-item label="Tool Token"><el-input v-model="form.accessToken" type="password" show-password autocomplete="new-password" :placeholder="needsToken ? '当前版本使用 Token，请为新版本重新输入' : '可留空；保存后不回显'" /></el-form-item>
          <el-form-item label="输入 Schema"><el-input v-model="inputSchemaText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="输出 Schema"><el-input v-model="outputSchemaText" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="前端返回字段"><el-input v-model="frontendFieldsText" placeholder="填写输出 Schema 的字段名，如 summary；多个用逗号分隔，留空则不返回" /></el-form-item>
          <el-form-item label="身份绑定"><el-checkbox v-model="bindExternalUser">服务端绑定 externalUserId 到请求头</el-checkbox></el-form-item>
          <el-form-item label="超时 / 结果上限"><el-input-number v-model="form.timeoutMs" :min="1000" :max="30000" /><el-input-number v-model="form.maxResultBytes" :min="1" :max="1048576" /></el-form-item>
          <el-form-item label="每轮次数"><el-input-number v-model="form.maxCallsPerTurn" :min="1" :max="10" /></el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="editorOpen = false">取消</el-button><el-button type="primary" :disabled="!canSave" @click="save">{{ editing ? '发布版本' : '创建' }}</el-button></template>
    </el-dialog>

    <el-drawer v-model="detailOpen" :title="detail?.name || 'Skill'" size="720px">
      <template v-if="detail">
        <p>{{ detail.visibility }} · {{ detail.status }} · 修订 {{ detail.revision }} · 引用 {{ detail.referenceCount }}</p>
        <div v-if="detail.visibility === 'PRIVATE' || isAdmin" class="toolbar">
          <el-button v-if="detail.status !== 'DISABLED'" @click="startVersion">发布新版本</el-button>
          <el-button v-if="detail.status === 'UNLISTED'" @click="status('PUBLISHED')">上架</el-button>
          <el-button v-if="detail.status === 'PUBLISHED'" @click="status('UNLISTED')">下架</el-button>
          <el-button v-if="detail.status !== 'DISABLED'" type="warning" @click="status('DISABLED')">停用</el-button>
          <el-button v-if="detail.status === 'DISABLED'" @click="status('PUBLISHED')">重新启用</el-button>
          <el-button type="danger" :disabled="detail.referenceCount > 0" @click="remove">删除</el-button>
        </div>
        <el-table :data="detail.versions"><el-table-column prop="versionNo" label="版本" width="75" /><el-table-column prop="skillType" label="类型" width="130" /><el-table-column prop="toolName" label="工具名" /><el-table-column prop="tokenSuffix" label="Token 后缀" width="120" /><el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.skillType === 'HTTP_TOOL' && row.versionId === detail?.currentVersionId" link @click="check">检查 TLS</el-button></template></el-table-column></el-table>
        <el-alert title="连接检查只验证目标地址、DNS 与 TLS，不调用 Tool 业务接口。已绑定版本保留作历史展示；停用会阻止后续调用。" type="info" :closable="false" class="notice" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { addSkillVersion, changeSkillStatus, checkSkillConnection, createOfficialSkill, createSkill, deleteSkill, getSkill, listSkills, type SkillDetail, type SkillSummary, type SkillVersion, type SkillVersionInput } from '@/api/developer/skill'
import useUserStore from '@/store/modules/user'

const blank = (): SkillVersionInput => ({ skillType: 'PROMPT', instructions: '', contextRequirements: {}, httpMethod: 'GET', timeoutMs: 5000, maxResultBytes: 65536, maxCallsPerTurn: 1 })
const loading = ref(false), editorOpen = ref(false), detailOpen = ref(false)
const isAdmin = computed(() => useUserStore().roles.includes('admin'))
const items = ref<SkillSummary[]>([]), detail = ref<SkillDetail>()
const editing = ref(false), official = ref(false), name = ref(''), description = ref(''), importText = ref('')
const form = reactive<SkillVersionInput>(blank())
const defaultInputSchema = '{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}'
const defaultOutputSchema = '{"type":"object","properties":{"summary":{"type":"string"}}}'
const inputSchemaText = ref(defaultInputSchema)
const outputSchemaText = ref(defaultOutputSchema)
const frontendFieldsText = ref(''), bindExternalUser = ref(true)
const needsToken = computed(() => !!editing.value && !!detail.value?.versions.find((v: SkillVersion) => v.versionId === detail.value?.currentVersionId)?.tokenSuffix)
const canSave = computed(() => (editing.value || !!name.value.trim()) && (form.skillType === 'PROMPT' ? !!form.instructions?.trim() :
  !!form.toolName?.trim() && !!form.toolUrl?.trim() && (!needsToken.value || !!form.accessToken?.trim())))
function reload() { loading.value = true; listSkills().then(res => { items.value = res.data?.items || [] }).finally(() => { loading.value = false }) }
function openDetail(id: string) { getSkill(id).then(res => { detail.value = res.data; detailOpen.value = true }) }
function refresh() { reload(); if (detail.value) openDetail(detail.value.skillId) }
function resetForm(version?: SkillVersionInput) { Object.keys(form).forEach(key => Reflect.deleteProperty(form, key)); Object.assign(form, blank(), version || {}) }
function startCreate(visibility: boolean) { editing.value = false; official.value = visibility; name.value = ''; description.value = ''; importText.value = ''; resetForm(); inputSchemaText.value = defaultInputSchema; outputSchemaText.value = defaultOutputSchema; frontendFieldsText.value = ''; bindExternalUser.value = true; editorOpen.value = true }
function startVersion() { if (!detail.value) return; editing.value = true; const current = detail.value.versions.find((v: SkillVersion) => v.versionId === detail.value?.currentVersionId); resetForm(current); form.accessToken = ''; inputSchemaText.value = current?.inputSchema ? JSON.stringify(current.inputSchema) : defaultInputSchema; outputSchemaText.value = current?.outputSchema ? JSON.stringify(current.outputSchema) : defaultOutputSchema; frontendFieldsText.value = current?.frontendFields?.join(', ') || ''; bindExternalUser.value = current?.identityBinding?.externalUserId === 'HEADER'; editorOpen.value = true }
function importPrompt() { try { const config = JSON.parse(importText.value); if (typeof config.instructions !== 'string' || !config.instructions.trim()) throw Error(); form.instructions = config.instructions; form.contextRequirements = config.contextRequirements || {}; form.importFormat = 'JSON'; ElMessage.success('已解析指令配置，保存时不会执行脚本。') } catch { ElMessage.error('JSON 配置须包含 instructions 字符串。') } }
function payload(): SkillVersionInput {
  if (form.skillType === 'PROMPT') return { skillType: 'PROMPT', instructions: form.instructions, contextRequirements: form.contextRequirements, ...(form.importFormat ? { importFormat: form.importFormat } : {}) }
  const inputSchema = JSON.parse(inputSchemaText.value), outputSchema = JSON.parse(outputSchemaText.value)
  return { skillType: 'HTTP_TOOL', toolName: form.toolName, toolUrl: form.toolUrl, httpMethod: form.httpMethod, contextRequirements: form.contextRequirements,
    inputSchema, outputSchema, accessToken: form.accessToken || undefined, requiresUserCredential: false,
    identityBinding: bindExternalUser.value ? { externalUserId: 'HEADER' } : {}, frontendFields: frontendFieldsText.value.split(',').map((x: string) => x.trim()).filter(Boolean),
    timeoutMs: form.timeoutMs, maxResultBytes: form.maxResultBytes, maxCallsPerTurn: form.maxCallsPerTurn }
}
function save() { let version: SkillVersionInput; try { version = payload() } catch { ElMessage.error('Schema 必须是有效 JSON。'); return }
  const action = editing.value && detail.value ? addSkillVersion(detail.value.skillId, detail.value.revision, version) :
    (official.value ? createOfficialSkill : createSkill)({ name: name.value, description: description.value, version })
  action.then(res => { editorOpen.value = false; form.accessToken = ''; ElMessage.success('Skill 已保存。'); if (res.data) openDetail(res.data.skillId); reload() }) }
function status(next: SkillSummary['status']) { if (!detail.value) return; const row = detail.value; ElMessageBox.prompt('请输入操作原因。', 'Skill 状态').then(({ value }: { value: string }) => changeSkillStatus(row.skillId, row.revision, next, value).then(refresh)) }
function remove() { if (!detail.value) return; const row = detail.value; ElMessageBox.confirm('删除后不可恢复；已被引用的 Skill 会拒绝删除。', '删除 Skill').then(() => deleteSkill(row.skillId, row.revision).then(() => { detailOpen.value = false; detail.value = undefined; reload() })) }
function check() { if (!detail.value) return; checkSkillConnection(detail.value.skillId).then(res => { if (res.data?.success) ElMessage.success('公网地址与 TLS 验证通过。'); else ElMessage.error(`连接失败：${res.data?.errorCode || 'UNKNOWN'}`) }) }
reload()
</script>

<style scoped>.toolbar { display: flex; gap: 8px; margin: 16px 0; }.full { width: 100%; }.notice { margin-top: 16px; }</style>
