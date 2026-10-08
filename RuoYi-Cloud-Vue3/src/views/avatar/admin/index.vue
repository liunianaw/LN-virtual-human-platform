<template>
  <div class="app-container">
    <header class="console-page-heading"><div><h1>官方角色制作</h1><p>上传参考形象，查看制作进度与动作候选。</p></div></header>
    <el-alert
      title="按页展示当前账号的官方角色制作任务；验收并发布后才可供应用选用。"
      type="info"
      :closable="false"
      show-icon
      class="mb12"
    />

    <el-row :gutter="16">
      <el-col :xs="24" :lg="10">
        <el-card header="1. 上传参考图并确认权利" shadow="never" class="mb16">
          <el-form label-width="100px">
            <el-form-item label="参考图片">
              <el-upload
                :auto-upload="false"
                :limit="1"
                accept="image/png,image/jpeg"
                :on-change="handleReferenceChange"
                :on-remove="handleReferenceRemove"
              >
                <el-button type="primary" plain icon="Upload">选择 PNG 或 JPEG</el-button>
                <template #tip><div class="el-upload__tip">文件不超过 10 MB，尺寸不超过 4096 × 4096。</div></template>
              </el-upload>
            </el-form-item>
            <el-form-item label="声明版本">
              <el-input v-model="rightsNoticeVersion" maxlength="32" />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="rightsConfirmed">
                我确认拥有上传参考图的必要权利，并同意用于本次 Avatar 制作。
              </el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="uploading" @click="submitReference">上传参考图</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="reference" :column="1" border size="small">
            <el-descriptions-item label="文件 ID">{{ reference.fileId }}</el-descriptions-item>
            <el-descriptions-item label="媒体类型">{{ reference.contentType || '—' }}</el-descriptions-item>
            <el-descriptions-item label="尺寸">{{ reference.width || '—' }} × {{ reference.height || '—' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card header="2. 创建整套制作任务" shadow="never">
          <el-form label-width="100px">
            <el-form-item label="角色名称" required>
              <el-input v-model="taskForm.name" maxlength="100" placeholder="例如：赤霜" />
            </el-form-item>
            <el-form-item label="官方服务" required>
              <el-select v-model="taskForm.officialServiceId" :loading="generationServicesLoading" :disabled="!generationServices.length" class="full-width" placeholder="请选择已启用的官方服务">
                <el-option
                  v-for="service in generationServices"
                  :key="service.serviceId"
                  :label="service.name + '（' + service.modelId + '）'"
                  :value="service.serviceId"
                />
              </el-select>
              <div class="form-tip">仅显示当前可用的官方 Avatar 制作服务。</div>
              <el-alert
                v-if="!generationServicesLoading && !generationServices.length"
                title="当前没有可用的官方 Avatar 制作服务，请联系管理员启用已验证的 qwen-image-3.0-pro 图像生成模型后再试。"
                type="warning"
                :closable="false"
                show-icon
                class="mt8"
              />
            </el-form-item>
            <el-alert title="管理员制作的角色将作为官方公共角色，发布后供所有平台用户选择。" type="info" :closable="false" class="mb16" />
            <el-form-item>
              <el-alert title="先补全为纯色背景的全身站立形象，再制作八个动作；形象补全按一个动作收费，当前为50积分。" type="info" :closable="false" />
              <el-button type="primary" :loading="creating" :disabled="!reference || !taskForm.officialServiceId" @click="createTask">开始制作</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card header="制作任务与候选验收" shadow="never">
          <el-form :inline="true" class="task-query">
            <el-form-item label="任务 ID">
              <el-input v-model.trim="taskIdToQuery" inputmode="numeric" maxlength="19" placeholder="请输入任务 ID" />
            </el-form-item>
            <el-form-item>
              <el-button icon="Search" @click="queryTask">查询任务</el-button>
            </el-form-item>
          </el-form>
          <el-alert v-if="queriedTask" :title="`查询任务 ${queriedTask.taskId}：${queriedTask.status}`" type="info" :closable="false"><el-button link type="primary" @click="openProduction(queriedTask)">打开制作详情</el-button></el-alert>
          <div class="table-tip">
            <span>按页查询当前账号的制作任务。</span>
            <el-button link type="primary" :loading="querying" @click="loadTasks">刷新列表</el-button>
          </div>
          <el-table v-loading="querying" :data="tasks" border>
            <el-table-column prop="taskId" label="任务 ID" width="100" />
            <el-table-column prop="avatarId" label="Avatar" width="100" />
            <el-table-column prop="avatarVersionId" label="候选版本" width="100" />
            <el-table-column label="状态" min-width="130">
              <template #default="scope">
                <el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag>
                <span v-if="scope.row.internalState" class="state-detail">{{ scope.row.internalState }}</span>
                <div v-if="scope.row.errorCode" role="status">{{ scope.row.errorCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="进度" width="130">
              <template #default="scope"><el-progress :percentage="scope.row.progress || 0" :stroke-width="8" /></template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="scope">
                <el-button link type="primary" @click="refreshTask(scope.row)">刷新</el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="!scope.row.avatarId || !scope.row.avatarVersionId"
                  @click="openProduction(scope.row)"
                >制作详情</el-button>
                <el-button link type="primary" @click="createInheritedVersion(scope.row)">新版本</el-button>
              </template>
            </el-table-column>
          </el-table>
          <pagination v-show="total > 0" :total="total" :page="pageNum" :limit="pageSize" :page-sizes="[10, 20, 50, 100]" :auto-scroll="false" @pagination="changePage" />
        </el-card>
      </el-col>
    </el-row>
    <el-dialog v-model="productionOpen" title="八动作制作详情" width="1080px" append-to-body @closed="stopProductionPolling">
      <el-skeleton v-if="productionLoading && !production" :rows="8" animated />
      <template v-else-if="production">
        <el-alert v-if="productionStatusMessage" :title="productionStatusMessage" type="warning" :closable="false" class="mb12" />
        <div class="production-summary">
          <span>已生成 {{ production.completedActionCount }}/8</span>
          <span>已确认 {{ production.acceptedActionCount }}/8</span>
          <el-tag :type="production.versionStatus === 'REVIEW' ? 'success' : 'warning'">{{ production.versionStatus }}</el-tag>
          <el-button link type="primary" :loading="productionLoading" @click="loadProduction()">刷新</el-button>
        </div>
        <el-row :gutter="12">
          <el-col v-if="production.characterCompletion" :span="24">
            <el-card shadow="never" class="mb12">
              <strong>角色形象补全</strong>
              <el-tag class="ml12">{{ production.characterCompletion.stage }}</el-tag>
              <p>先生成纯色背景的全身默认站立图，完成后自动用于八个动作。</p>
              <el-image v-if="production.characterCompletion.imageUrl" :src="production.characterCompletion.imageUrl" style="width: 120px; height: 160px" fit="contain" :preview-src-list="[production.characterCompletion.imageUrl]" />
              <p v-if="production.characterCompletion.errorCode">{{ production.characterCompletion.errorCode }}</p>
              <el-button v-if="production.characterCompletion.allowedOperations.includes('recovery')" size="small" @click="recoverAction(production.characterCompletion)">核对 / 恢复原补全结果</el-button>
            </el-card>
          </el-col>
          <el-col v-for="action in production.actions" :key="action.actionCode" :xs="24" :sm="12" :lg="6">
            <el-card shadow="never" class="action-card">
              <template #header>
                <div class="action-header">
                  <strong>{{ actionLabel(action.actionCode) }}</strong>
                  <el-tag size="small" :type="actionStageType(action.stage)">{{ action.stage }}</el-tag>
                </div>
              </template>
              <ActionPreview
                v-if="actionPreviews[action.actionCode]"
                :url="actionPreviews[action.actionCode].atlasUrl"
                :frames="actionPreviews[action.actionCode].frameLayout?.frames || []"
                :fps="actionPreviews[action.actionCode].fps"
                :loop="Boolean(actionPreviews[action.actionCode].loopEnabled)"
              />
              <el-empty v-else description="动作尚未预览" :image-size="56" />
              <div class="action-meta">
                <span>结果 {{ action.resultIds.length }}</span>
                <span>{{ action.acceptedResultId ? '已确认' : '待确认' }}</span>
              </div>
              <div v-if="action.stageStartedAt" class="action-timing">{{ stageTiming(action) }}</div>
              <div v-if="action.errorCode" class="action-error">{{ action.safeMessage || action.errorCode }}</div>
              <div class="action-buttons">
                <el-button v-if="action.allowedOperations.includes('preview')" size="small" @click="previewAction(action)">预览</el-button>
                <el-button v-if="action.allowedOperations.includes('select')" size="small" type="success" plain @click="acceptAction(action)">确认采用</el-button>
                <el-button v-if="action.allowedOperations.includes('recovery')" size="small" type="primary" plain @click="recoverAction(action)">核对 / 恢复</el-button>
                <el-button v-if="action.allowedOperations.includes('discard')" size="small" plain @click="discardAction(action)">保留旧结果</el-button>
                <el-button v-if="action.allowedOperations.includes('regenerate')" size="small" type="warning" plain @click="regenerateAction(action)">重做</el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>
        <div class="assembly-bar">
          <span v-if="!production.canAssemble">确认八个动作且没有运行中或待核对任务后才能组装。</span>
          <el-button type="primary" :loading="assembling" :disabled="!production.canAssemble" @click="assembleProduction">组装整套并进入验收</el-button>
          <el-button v-if="production.versionStatus === 'REVIEW'" type="success" plain @click="openFinalPreview">整套预览 / 发布</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="previewOpen" title="候选 Avatar 预览与人工验收" width="900px" append-to-body>
      <el-skeleton v-if="previewLoading" :rows="6" animated />
      <template v-else-if="preview">
        <el-alert
          :title="preview.status === 'REVIEW' ? '请逐项检查全身、手部、动作过渡和透明边缘。' : '当前版本不处于 REVIEW，发布操作不可用。'"
          :type="preview.status === 'REVIEW' ? 'warning' : 'info'"
          :closable="false"
          show-icon
          class="mb12"
        />
        <el-row :gutter="16">
          <el-col :xs="24" :sm="10">
            <el-image v-if="preview.previewUrl || preview.baseImageUrl" :src="preview.previewUrl || preview.baseImageUrl" fit="contain" class="preview-image" />
            <el-empty v-else description="后端尚未提供可读取的预览文件" :image-size="80" />
          </el-col>
          <el-col :xs="24" :sm="14">
            <el-descriptions :column="2" border size="small" class="mb12">
              <el-descriptions-item label="状态">{{ preview.status }}</el-descriptions-item>
              <el-descriptions-item label="帧规格">{{ preview.frameWidth || '—' }} × {{ preview.frameHeight || '—' }}</el-descriptions-item>
              <el-descriptions-item label="Anchor">{{ preview.anchorX ?? '—' }}, {{ preview.anchorY ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="动作数">{{ preview.actions?.length || 0 }} / 8</el-descriptions-item>
            </el-descriptions>
            <el-table :data="preview.actions || []" size="small" border max-height="260">
              <el-table-column prop="actionCode" label="动作" width="110" />
              <el-table-column prop="frameCount" label="帧数" width="70" />
              <el-table-column prop="fps" label="FPS" width="70" />
              <el-table-column label="循环" width="70"><template #default="scope">{{ scope.row.loopEnabled ? '是' : '否' }}</template></el-table-column>
              <el-table-column prop="frameLayout" label="布局" />
            </el-table>
          </el-col>
        </el-row>
        <el-divider content-position="left">八动作动态预览</el-divider>
        <el-row :gutter="12" class="final-action-grid">
          <el-col v-for="action in preview.actions || []" :key="action.actionCode" :xs="24" :sm="12" :md="6">
            <div class="final-action-card">
              <strong>{{ actionLabel(action.actionCode) }}</strong>
              <ActionPreview
                v-if="action.atlasUrl && finalActionFrames(action.frameLayout).length"
                :url="action.atlasUrl"
                :frames="finalActionFrames(action.frameLayout)"
                :fps="action.fps"
                :loop="Boolean(action.loopEnabled)"
              />
              <span v-else>动作预览文件不可用</span>
            </div>
          </el-col>
        </el-row>
        <el-divider content-position="left">发布确认</el-divider>
        <el-checkbox v-model="reviewAccepted" :disabled="preview.status !== 'REVIEW'">
          已完成视觉检查，确认全身、手部、动作过渡和透明边缘符合预期。
        </el-checkbox>
        <el-input
          v-model="reviewNote"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="填写人工验收说明"
          class="review-note"
          :disabled="preview.status !== 'REVIEW'"
        />
      </template>
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="publishing"
          :disabled="previewReadOnly || !preview || preview.status !== 'REVIEW' || !reviewAccepted || !reviewNote.trim()"
          @click="publishPreview"
        >确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AdminAvatarProduction">
import {
  assembleAvatarVersion,
  createAvatarGenerationTask,
  createAvatarVersion,
  discardAvatarActionAttempt,
  getAvatarActionResultPreview,
  getAvatarDetail,
  getAvatarGenerationTask,
  getAvatarProduction,
  getAvatarVersionPreview,
  listAvatarGenerationServices,
  pageAvatarGenerationTasks,
  publishAvatarVersion,
  recoverAvatarActionAttempt,
  regenerateAvatarAction,
  selectAvatarActionResult,
  uploadAvatarReference,
  type AvatarGenerationTask,
  type AvatarGenerationService,
  type AvatarProductionAction,
  type AvatarProductionSnapshot,
  type AvatarActionResultPreview,
  type AvatarReferenceFile,
  type AvatarVersionPreview
} from '@/api/asset/avatar'
import ActionPreview from '../ActionPreview.vue'
import useUserStore from '@/store/modules/user'

const { proxy } = getCurrentInstance()
const userStore = useUserStore()
const router = useRouter()
const pageNum = ref(1), pageSize = ref(20), total = ref(0)
const selectedReferenceFile = ref<File>()
const reference = ref<AvatarReferenceFile>()
const rightsConfirmed = ref(false)
const rightsNoticeVersion = ref('avatar-reference-v1')
const uploading = ref(false)
const creating = ref(false)
const generationServicesLoading = ref(false)
const querying = ref(false)
const publishing = ref(false)
const assembling = ref(false)
const taskIdToQuery = ref('')
const tasks = ref<AvatarGenerationTask[]>([])
const queriedTask = ref<AvatarGenerationTask>()
const generationServices = ref<AvatarGenerationService[]>([])
const previewOpen = ref(false)
const previewLoading = ref(false)
const preview = ref<AvatarVersionPreview>()
const previewReadOnly = ref(false)
const reviewAccepted = ref(false)
const reviewNote = ref('')
const productionOpen = ref(false)
const productionLoading = ref(false)
const production = ref<AvatarProductionSnapshot>()
const productionStatusMessage = ref('')
const productionIdentity = reactive({ avatarId: '', versionId: '' })
const actionPreviews = reactive<Record<string, AvatarActionResultPreview>>({})
let productionTimer: number | undefined
let productionPollFailures = 0
const taskForm = reactive({
  name: '',
  officialServiceId: undefined as string | undefined
})

function handleReferenceChange(file: { raw?: File }) {
  selectedReferenceFile.value = file.raw
}

function handleReferenceRemove() {
  selectedReferenceFile.value = undefined
}

function submitReference() {
  if (!selectedReferenceFile.value || !rightsConfirmed.value || !rightsNoticeVersion.value.trim()) {
    proxy?.$modal.msgWarning('请选择参考图、确认权利，并填写声明版本。')
    return
  }
  const formData = new FormData()
  formData.append('file', selectedReferenceFile.value)
  formData.append('rightsNoticeVersion', rightsNoticeVersion.value.trim())
  formData.append('rightsConfirmed', 'true')
  uploading.value = true
  uploadAvatarReference(formData).then(response => {
    if (!response.data?.fileId) throw new Error('上传接口未返回文件 ID')
    reference.value = response.data
    proxy?.$modal.msgSuccess('参考图已上传。')
  }).finally(() => {
    uploading.value = false
  })
}

function createTask() {
  if (!reference.value?.fileId || !taskForm.name.trim() || !taskForm.officialServiceId) {
    proxy?.$modal.msgWarning('请先上传参考图、填写角色名称并选择官方服务。')
    return
  }
  const selectedService = generationServices.value.find((service: AvatarGenerationService) => service.serviceId === taskForm.officialServiceId)
  if (!selectedService) {
    proxy?.$modal.msgWarning('制作服务列表已变化，请重新选择。')
    return
  }
  creating.value = true
  const createRequest = {
    sourceFileId: reference.value.fileId,
    officialServiceId: taskForm.officialServiceId,
    expectedServiceRevision: selectedService.revision,
    requestId: crypto.randomUUID(),
    name: taskForm.name.trim()
  }
  createAvatarGenerationTask(createRequest).then(response => {
    if (!response.data?.taskId) throw new Error('制作接口未返回任务 ID')
    pageNum.value = 1; loadTasks()
    proxy?.$modal.msgSuccess('制作任务已受理。')
    openProduction(response.data)
  }).finally(() => {
    creating.value = false
  })
}

function queryTask() {
  if (!/^[1-9]\d*$/.test(taskIdToQuery.value)) {
    proxy?.$modal.msgWarning('请输入有效的任务 ID。')
    return
  }
  refreshTaskById(taskIdToQuery.value)
}

function refreshTask(task: AvatarGenerationTask) {
  refreshTaskById(task.taskId)
}

function refreshTaskById(taskId: string) {
  querying.value = true
  getAvatarGenerationTask(taskId).then(response => {
    if (!response.data?.taskId) throw new Error('任务查询接口未返回任务数据')
    queriedTask.value = response.data
    upsertTask(response.data)
  }).finally(() => {
    querying.value = false
  })
}

function changePage({ page, limit }: { page: number; limit: number }) { pageNum.value = pageSize.value === limit ? page : 1; pageSize.value = limit; loadTasks() }

let listRequest = 0
function loadTasks() {
  const request = ++listRequest
  querying.value = true
  pageAvatarGenerationTasks({ pageNum: pageNum.value, pageSize: pageSize.value }).then(response => {
    if (request !== listRequest) return
    tasks.value = response.data?.items || []; total.value = response.data?.total || 0
  }).finally(() => {
    if (request === listRequest) querying.value = false
  })
}

function loadGenerationServices() {
  generationServicesLoading.value = true
  listAvatarGenerationServices().then(response => {
    generationServices.value = response.data || []
    if (!generationServices.value.some((service: AvatarGenerationService) => service.serviceId === taskForm.officialServiceId)) {
      taskForm.officialServiceId = undefined
    }
  }).finally(() => {
    generationServicesLoading.value = false
  })
}

function createInheritedVersion(task: AvatarGenerationTask) {
  getAvatarDetail(task.avatarId).then(response => {
    const detail = response.data
    if (!detail?.currentVersionId || !detail.revision) throw new Error('角色尚无可继承的已发布版本。')
    return createAvatarVersion(task.avatarId, {
      requestId: crypto.randomUUID(), expectedAvatarRevision: detail.revision, baseVersionId: detail.currentVersionId
    })
  }).then(response => {
    if (!response.data?.versionId) throw new Error('新版本接口未返回候选版本。')
    proxy?.$modal.msgSuccess('已创建同一角色的新候选版本，旧发布版本保持不变。')
    openProduction({ avatarId: response.data.avatarId, avatarVersionId: response.data.versionId } as AvatarGenerationTask)
  })
}

function upsertTask(task: AvatarGenerationTask) {
  const index = tasks.value.findIndex((item: AvatarGenerationTask) => item.taskId === task.taskId)
  if (index !== -1) tasks.value.splice(index, 1, task)
}

function openProduction(task: AvatarGenerationTask) {
  productionIdentity.avatarId = task.avatarId
  productionIdentity.versionId = task.avatarVersionId
  production.value = undefined
  productionStatusMessage.value = ''
  productionPollFailures = 0
  Object.keys(actionPreviews).forEach(key => delete actionPreviews[key])
  productionOpen.value = true
  loadProduction()
  startProductionPolling()
}

function loadProduction(silent = false) {
  if (!productionIdentity.avatarId || !productionIdentity.versionId || productionLoading.value) return
  if (!silent) productionLoading.value = true
  getAvatarProduction(productionIdentity.avatarId, productionIdentity.versionId).then(response => {
    if (!response.data?.versionId) throw new Error('制作进度接口未返回候选版本')
    production.value = response.data
    productionPollFailures = 0
    productionStatusMessage.value = ''
    if (hasRunningActions(response.data)) startProductionPolling(3000)
    else stopProductionPolling()
  }).catch(() => {
    productionPollFailures++
    productionStatusMessage.value = '状态暂未更新；网络恢复后页面会继续查询，不会把任务标记为失败。'
    startProductionPolling(Math.min(15000, 3000 * (2 ** Math.min(productionPollFailures - 1, 3))))
  }).finally(() => { productionLoading.value = false })
}

function startProductionPolling(delay = 3000) {
  stopProductionPolling()
  productionTimer = window.setTimeout(() => {
    productionTimer = undefined
    if (!productionOpen.value) return
    if (document.hidden) {
      startProductionPolling(delay)
      return
    }
    if (!production.value || hasRunningActions(production.value)) loadProduction(true)
  }, delay)
}

function stopProductionPolling() {
  if (productionTimer !== undefined) window.clearTimeout(productionTimer)
  productionTimer = undefined
}

function hasRunningActions(snapshot: AvatarProductionSnapshot) {
  return Boolean(snapshot.characterCompletion && isActionActive(snapshot.characterCompletion.stage)) || snapshot.actions.some(action => isActionActive(action.stage))
}

function isActionActive(stage: string) {
  return ['QUEUED', 'READY', 'RUNNING', 'POLLING'].includes(stage)
}

function stageTiming(action: AvatarProductionAction) {
  const started = action.stageStartedAt ? Date.parse(action.stageStartedAt) : Number.NaN
  if (!Number.isFinite(started)) return ''
  const label = new Date(started).toLocaleString()
  if (!isActionActive(action.stage)) return `阶段开始：${label}`
  const seconds = Math.max(0, Math.floor((Date.now() - started) / 1000))
  return `阶段开始：${label} · 已等待 ${Math.floor(seconds / 60)}分${seconds % 60}秒`
}

function latestResult(action: AvatarProductionAction) {
  return action.resultIds[action.resultIds.length - 1]
}

function previewAction(action: AvatarProductionAction) {
  const resultId = latestResult(action)
  if (!resultId || !production.value) return
  getAvatarActionResultPreview(production.value.avatarId, production.value.versionId, action.actionCode, resultId).then(response => {
    if (!response.data?.atlasUrl) throw new Error('动作预览接口未返回图集')
    actionPreviews[action.actionCode] = response.data
  })
}

function acceptAction(action: AvatarProductionAction) {
  const resultId = latestResult(action)
  if (!resultId || !production.value) return
  selectAvatarActionResult(production.value.avatarId, production.value.versionId, action.actionCode, {
    requestId: crypto.randomUUID(), resultId, expectedActionRevision: action.actionRevision, visualAccepted: true
  }).then(() => {
    proxy?.$modal.msgSuccess(`${actionLabel(action.actionCode)}已确认。`)
    loadProduction()
  })
}

async function regenerateAction(action: AvatarProductionAction) {
  if (!production.value || isActionActive(action.stage)) return
  let acknowledgeUncertainCharge = false
  let supersedesAttemptId: string | undefined
  if (action.stage === 'UNKNOWN') {
    await proxy?.$modal.confirm('上次请求结果未知，重新生成可能产生重复费用。确认继续吗？')
    acknowledgeUncertainCharge = true
    supersedesAttemptId = action.latestAttemptId || undefined
  }
  regenerateAvatarAction(production.value.avatarId, production.value.versionId, action.actionCode, {
    requestId: crypto.randomUUID(), expectedActionRevision: action.actionRevision,
    acknowledgeUncertainCharge, supersedesAttemptId
  }).then(() => {
    proxy?.$modal.msgSuccess(`已提交${actionLabel(action.actionCode)}重做。`)
    loadProduction()
    startProductionPolling()
  })
}

function recoverAction(action: AvatarProductionAction) {
  if (!production.value || !action.latestAttemptId) return
  recoverAvatarActionAttempt(production.value.avatarId, production.value.versionId, action.actionCode, action.latestAttemptId, {
    requestId: crypto.randomUUID(), expectedActionRevision: action.actionRevision
  }).then(() => {
    proxy?.$modal.msgSuccess(`已安排核对${actionLabel(action.actionCode)}的原任务，不会重新发起生成。`)
    loadProduction()
    startProductionPolling()
  })
}

async function discardAction(action: AvatarProductionAction) {
  if (!production.value || !action.latestAttemptId || !action.acceptedResultId) return
  await proxy?.$modal.confirm('确认保留上一次已采用结果，并关闭本轮选用吗？已提交给厂商的请求无法保证取消。')
  discardAvatarActionAttempt(production.value.avatarId, production.value.versionId, action.actionCode, action.latestAttemptId, {
    requestId: crypto.randomUUID(), expectedActionRevision: action.actionRevision, retainResultId: action.acceptedResultId
  }).then(() => {
    proxy?.$modal.msgSuccess(`已保留${actionLabel(action.actionCode)}的旧结果。`)
    loadProduction()
  })
}

function assembleProduction() {
  if (!production.value || !production.value.canAssemble) return
  const selectedResults = production.value.actions.map((action: AvatarProductionAction) => ({
    actionCode: action.actionCode, resultId: action.acceptedResultId || ''
  }))
  if (selectedResults.some((item: { actionCode: string; resultId: string }) => !item.resultId)) return
  assembling.value = true
  assembleAvatarVersion(production.value.avatarId, production.value.versionId, {
    requestId: crypto.randomUUID(), expectedCandidateRevision: production.value.candidateRevision, selectedResults
  }).then(() => {
    proxy?.$modal.msgSuccess('八动作已组装，可以进行整套预览。')
    loadProduction()
  }).finally(() => { assembling.value = false })
}

function openFinalPreview() {
  if (!production.value) return
  openPreview({ avatarId: production.value.avatarId, avatarVersionId: production.value.versionId } as AvatarGenerationTask)
}

function openPreview(task: AvatarGenerationTask, readOnly = false) {
  previewOpen.value = true
  previewReadOnly.value = readOnly
  previewLoading.value = true
  preview.value = undefined
  reviewAccepted.value = false
  reviewNote.value = ''
  getAvatarVersionPreview(task.avatarId, task.avatarVersionId).then(response => {
    if (!response.data?.versionId) throw new Error('预览接口未返回候选版本')
    preview.value = response.data
  }).finally(() => {
    previewLoading.value = false
  })
}

function publishPreview() {
  if (!preview.value || !reviewAccepted.value || !reviewNote.value.trim()) return
  publishing.value = true
  publishAvatarVersion(preview.value.avatarId, preview.value.versionId, {
    visualAccepted: true,
    reviewNote: reviewNote.value.trim()
  }).then(response => {
    if (!response.data?.versionId) throw new Error('发布接口未返回版本状态')
    preview.value = response.data
    proxy?.$modal.msgSuccess('候选版本已发布。')
  }).finally(() => {
    publishing.value = false
  })
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCEEDED' || status === 'PUBLISHED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PROCESSING' || status === 'QUEUED') return 'warning'
  return 'info'
}

function actionStageType(stage: string): 'success' | 'warning' | 'danger' | 'info' {
  if (stage === 'READY_FOR_REVIEW' || stage === 'SUCCEEDED') return 'success'
  if (stage === 'FAILED' || stage === 'UNKNOWN') return 'danger'
  if (isActionActive(stage)) return 'warning'
  return 'info'
}

function actionLabel(action: string) {
  return ({ character_completion: '角色形象补全', idle: '待机', speaking: '说话', listening: '倾听', thinking: '思考', nod: '点头', shake_head: '摇头', wave: '挥手', happy: '开心' } as Record<string, string>)[action] || action
}

function finalActionFrames(layout?: string): Array<{ x: number; y: number; width: number; height: number }> {
  if (!layout) return []
  try {
    const parsed = JSON.parse(layout)
    return Array.isArray(parsed.frames) ? parsed.frames : []
  } catch {
    return []
  }
}

function handleVisibilityChange() {
  if (!document.hidden && productionOpen.value && (!production.value || hasRunningActions(production.value))) {
    stopProductionPolling()
    loadProduction(true)
  }
}

onMounted(() => {
  if (!userStore.isAdmin) { router.replace('/401'); return }
  document.addEventListener('visibilitychange', handleVisibilityChange)
  loadTasks()
  loadGenerationServices()
})
onUnmounted(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  stopProductionPolling()
})
</script>

<style scoped>
.full-width { width: 100%; }
.form-tip, .table-tip { color: var(--el-text-color-secondary); font-size: 14px; line-height: 20px; }
.mt8 { margin-top: 8px; }
.mt16 { margin-top: 16px; }
.task-query { margin-bottom: 8px; }
.state-detail { margin-left: 6px; color: var(--el-text-color-secondary); font-size: 14px; }
.preview-image { width: 100%; min-height: 260px; border: 1px solid var(--el-border-color-lighter); }
.review-note { margin-top: 12px; }
.production-summary, .action-header, .action-meta, .assembly-bar { display: flex; align-items: center; gap: 10px; }
.production-summary { margin-bottom: 12px; }
.action-header { justify-content: space-between; }
.action-card { margin-bottom: 12px; }
.action-meta { justify-content: space-between; margin: 8px 0; color: var(--el-text-color-secondary); font-size: 14px; }
.action-timing { margin-bottom: 8px; color: var(--el-text-color-secondary); font-size: 14px; }
.action-error { min-height: 20px; color: var(--el-color-danger); font-size: 14px; }
.action-buttons { display: flex; flex-wrap: wrap; gap: 6px; }
.action-buttons :deep(.el-button + .el-button) { margin-left: 0; }
.assembly-bar { justify-content: flex-end; margin-top: 8px; }
.assembly-bar > span { margin-right: auto; color: var(--el-text-color-secondary); font-size: 14px; }
.final-action-grid { max-height: 460px; overflow-y: auto; }
.final-action-card { border: 1px solid var(--el-border-color-lighter); margin-bottom: 12px; padding: 8px; }
</style>
