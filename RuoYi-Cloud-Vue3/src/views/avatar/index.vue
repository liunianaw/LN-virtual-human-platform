<template>
  <div class="app-container">
    <el-alert
      title="任务列表仅显示当前账号最近 100 条记录。"
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
            <el-form-item>
              <el-button type="primary" :loading="creating" :disabled="!reference || !taskForm.officialServiceId" @click="createTask">开始制作</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card header="制作任务与候选验收" shadow="never">
          <el-form :inline="true" class="task-query">
            <el-form-item label="任务 ID">
              <el-input-number v-model="taskIdToQuery" :min="1" :controls="false" />
            </el-form-item>
            <el-form-item>
              <el-button icon="Search" @click="queryTask">查询任务</el-button>
            </el-form-item>
          </el-form>
          <div class="table-tip">
            <span>仅显示当前账号最近 100 条任务。</span>
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
              </template>
            </el-table-column>
            <el-table-column label="进度" width="130">
              <template #default="scope"><el-progress :percentage="scope.row.progress || 0" :stroke-width="8" /></template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="scope">
                <el-button link type="primary" @click="refreshTask(scope.row)">刷新</el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="!scope.row.avatarId || !scope.row.avatarVersionId"
                  @click="openPreview(scope.row)"
                >预览 / 验收</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

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
          :disabled="!preview || preview.status !== 'REVIEW' || !reviewAccepted || !reviewNote.trim()"
          @click="publishPreview"
        >确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AvatarProduction">
import {
  createAvatarGenerationTask,
  getAvatarGenerationTask,
  getAvatarVersionPreview,
  listAvatarGenerationServices,
  listAvatarGenerationTasks,
  publishAvatarVersion,
  uploadAvatarReference,
  type AvatarGenerationTask,
  type AvatarGenerationService,
  type AvatarReferenceFile,
  type AvatarVersionPreview
} from '@/api/asset/avatar'

const { proxy } = getCurrentInstance()
const selectedReferenceFile = ref<File>()
const reference = ref<AvatarReferenceFile>()
const rightsConfirmed = ref(false)
const rightsNoticeVersion = ref('avatar-reference-v1')
const uploading = ref(false)
const creating = ref(false)
const generationServicesLoading = ref(false)
const querying = ref(false)
const publishing = ref(false)
const taskIdToQuery = ref<number>()
const tasks = ref<AvatarGenerationTask[]>([])
const generationServices = ref<AvatarGenerationService[]>([])
const previewOpen = ref(false)
const previewLoading = ref(false)
const preview = ref<AvatarVersionPreview>()
const reviewAccepted = ref(false)
const reviewNote = ref('')
const taskForm = reactive({
  name: '',
  officialServiceId: undefined as number | undefined
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
  creating.value = true
  createAvatarGenerationTask({
    sourceFileId: reference.value.fileId,
    officialServiceId: taskForm.officialServiceId,
    requestId: crypto.randomUUID(),
    name: taskForm.name.trim()
  }).then(response => {
    if (!response.data?.taskId) throw new Error('制作接口未返回任务 ID')
    upsertTask(response.data)
    proxy?.$modal.msgSuccess('制作任务已受理。')
  }).finally(() => {
    creating.value = false
  })
}

function queryTask() {
  if (!taskIdToQuery.value) {
    proxy?.$modal.msgWarning('请输入任务 ID。')
    return
  }
  refreshTaskById(taskIdToQuery.value)
}

function refreshTask(task: AvatarGenerationTask) {
  refreshTaskById(task.taskId)
}

function refreshTaskById(taskId: number) {
  querying.value = true
  getAvatarGenerationTask(taskId).then(response => {
    if (!response.data?.taskId) throw new Error('任务查询接口未返回任务数据')
    upsertTask(response.data)
  }).finally(() => {
    querying.value = false
  })
}

function loadTasks() {
  querying.value = true
  listAvatarGenerationTasks().then(response => {
    tasks.value = response.data || []
  }).finally(() => {
    querying.value = false
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

function upsertTask(task: AvatarGenerationTask) {
  const index = tasks.value.findIndex((item: AvatarGenerationTask) => item.taskId === task.taskId)
  if (index === -1) tasks.value.unshift(task)
  else tasks.value.splice(index, 1, task)
}

function openPreview(task: AvatarGenerationTask) {
  previewOpen.value = true
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

onMounted(() => {
  loadTasks()
  loadGenerationServices()
})
</script>

<style scoped>
.full-width { width: 100%; }
.form-tip, .table-tip { color: var(--el-text-color-secondary); font-size: 12px; line-height: 20px; }
.mt8 { margin-top: 8px; }
.task-query { margin-bottom: 8px; }
.state-detail { margin-left: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.preview-image { width: 100%; min-height: 260px; border: 1px solid var(--el-border-color-lighter); }
.review-note { margin-top: 12px; }
</style>
