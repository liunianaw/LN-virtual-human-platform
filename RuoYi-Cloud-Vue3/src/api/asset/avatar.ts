import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface AvatarReferenceFile {
  fileId: string
  contentType?: string
  sizeBytes?: number
  width?: number
  height?: number
  readUrl?: string
}

export interface CreateAvatarGenerationTaskRequest {
  sourceFileId: string
  officialServiceId: string
  expectedServiceRevision: number
  requestId: string
  name: string
}

export interface AvatarGenerationService {
  serviceId: string
  name: string
  providerCode: string
  modelId: string
  revision: number
}

export interface AvatarGenerationTask {
  taskId: string
  avatarId: string
  avatarVersionId: string
  sourceFileId: string
  requestId: string
  status: string
  internalState?: string
  errorCode?: string
  progress?: number
  createdAt?: string
}

export interface AvatarActionPreview {
  actionCode: string
  frameCount: number
  fps: number
  loopEnabled: boolean
  frameLayout?: string
  atlasUrl?: string
  previewUrl?: string
  expiresAt?: string
}

export interface AvatarVersionPreview {
  avatarId: string
  versionId: string
  status: string
  frameWidth?: number
  frameHeight?: number
  anchorX?: number
  anchorY?: number
  baseImageUrl?: string
  manifestUrl?: string
  previewUrl?: string
  actions?: AvatarActionPreview[]
}

export interface PublishAvatarVersionRequest {
  visualAccepted: boolean
  reviewNote: string
}

export interface AvatarProductionAction {
  actionCode: string
  actionRevision: number
  stage: string
  latestAttemptId?: string | null
  selectedResultId?: string | null
  acceptedResultId?: string | null
  resultIds: string[]
  errorCode?: string | null
  safeMessage?: string | null
  nextRetryAt?: string | null
  stageStartedAt?: string | null
  allowedOperations: string[]
}

export interface AvatarProductionSnapshot {
  avatarId: string
  versionId: string
  candidateRevision: number
  versionStatus: string
  assemblyStage: string
  completedActionCount: number
  acceptedActionCount: number
  totalActionCount: number
  canAssemble: boolean
  actions: AvatarProductionAction[]
}

export interface AvatarActionResultPreview {
  resultId: string
  actionCode: string
  frameCount: number
  fps: number
  loopEnabled: boolean
  frameLayout: { frames?: Array<{ x: number; y: number; width: number; height: number }> }
  atlasUrl: string
  expiresAt?: string
  qaReport?: Record<string, unknown>
}

export interface AvatarCatalogItem {
  avatarId: string
  versionId?: string | null
  currentVersionId?: string | null
  name: string
  visibility: 'PRIVATE' | 'OFFICIAL'
  status: string
  revision?: number
  previewUrl?: string | null
  creatorAccountId?: string
  taskCount?: number
  versionCount?: number
}

export interface AvatarDetail extends AvatarCatalogItem {
  owned: boolean | number
  versions: Array<{ versionId: string; versionNo: number; status: string; createdAt?: string; owned: boolean | number }>
}

export function uploadAvatarReference(data: FormData): Promise<AjaxResult<AvatarReferenceFile>> {
  return request({
    url: '/system/asset/files',
    method: 'post',
    headers: { 'Content-Type': 'multipart/form-data' },
    data
  })
}

export function createAvatarGenerationTask(data: CreateAvatarGenerationTaskRequest): Promise<AjaxResult<AvatarGenerationTask>> {
  return request({
    url: '/system/asset/generation-tasks',
    method: 'post',
    data
  })
}

export function createOfficialAvatarGenerationTask(data: CreateAvatarGenerationTaskRequest): Promise<AjaxResult<AvatarGenerationTask>> {
  return request({
    url: '/system/asset/admin/public-generation-tasks',
    method: 'post',
    data
  })
}

export function listAvatarGenerationTasks(): Promise<AjaxResult<AvatarGenerationTask[]>> {
  return request({
    url: '/system/asset/generation-tasks',
    method: 'get'
  })
}

export function listAvatarGenerationServices(): Promise<AjaxResult<AvatarGenerationService[]>> {
  return request({
    url: '/system/asset/generation-services',
    method: 'get'
  })
}

export function getAvatarGenerationTask(taskId: string): Promise<AjaxResult<AvatarGenerationTask>> {
  return request({
    url: '/system/asset/generation-tasks/' + taskId,
    method: 'get'
  })
}

export function getAvatarVersionPreview(avatarId: string, versionId: string): Promise<AjaxResult<AvatarVersionPreview>> {
  return request({
    url: '/system/asset/avatars/' + avatarId + '/versions/' + versionId + '/preview',
    method: 'get'
  })
}

export function publishAvatarVersion(
  avatarId: string,
  versionId: string,
  data: PublishAvatarVersionRequest
): Promise<AjaxResult<AvatarVersionPreview>> {
  return request({
    url: '/system/asset/avatars/' + avatarId + '/versions/' + versionId + '/publish',
    method: 'post',
    data
  })
}

export function getAvatarProduction(avatarId: string, versionId: string): Promise<AjaxResult<AvatarProductionSnapshot>> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions/${versionId}/production`, method: 'get' })
}

export function getAvatarActionResultPreview(avatarId: string, versionId: string, actionCode: string, resultId: string): Promise<AjaxResult<AvatarActionResultPreview>> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions/${versionId}/actions/${actionCode}/results/${resultId}/preview`, method: 'get' })
}

export function selectAvatarActionResult(avatarId: string, versionId: string, actionCode: string, data: {
  requestId: string; resultId: string; expectedActionRevision: number; visualAccepted: true
}): Promise<AjaxResult> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions/${versionId}/actions/${actionCode}/selection`, method: 'post', data })
}

export function regenerateAvatarAction(avatarId: string, versionId: string, actionCode: string, data: {
  requestId: string; expectedActionRevision: number; acknowledgeUncertainCharge: boolean; supersedesAttemptId?: string
}): Promise<AjaxResult> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions/${versionId}/actions/${actionCode}/generations`, method: 'post', data })
}

export function recoverAvatarActionAttempt(avatarId: string, versionId: string, actionCode: string, attemptId: string, data: {
  requestId: string; expectedActionRevision: number
}): Promise<AjaxResult> {
  return request({
    url: `/system/asset/avatars/${avatarId}/versions/${versionId}/actions/${actionCode}/attempts/${attemptId}/recovery`,
    method: 'post', data
  })
}

export function discardAvatarActionAttempt(avatarId: string, versionId: string, actionCode: string, attemptId: string, data: {
  requestId: string; expectedActionRevision: number; retainResultId: string
}): Promise<AjaxResult> {
  return request({
    url: `/system/asset/avatars/${avatarId}/versions/${versionId}/actions/${actionCode}/attempts/${attemptId}/discard`,
    method: 'post', data
  })
}

export function assembleAvatarVersion(avatarId: string, versionId: string, data: {
  requestId: string; expectedCandidateRevision: number; selectedResults: Array<{ actionCode: string; resultId: string }>
}): Promise<AjaxResult> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions/${versionId}/assemble`, method: 'post', data })
}

export function createAvatarVersion(avatarId: string, data: {
  requestId: string; expectedAvatarRevision: number; baseVersionId?: string;
  sourceFileId?: string; officialServiceId?: string; expectedServiceRevision?: number
}): Promise<AjaxResult<{ avatarId: string; versionId: string; candidateRevision: number; taskId?: string | null }>> {
  return request({ url: `/system/asset/avatars/${avatarId}/versions`, method: 'post', data })
}

export function listPublicAvatars(pageNum = 1, pageSize = 20): Promise<AjaxResult<{ items: AvatarCatalogItem[]; total: number }>> {
  return request({ url: '/system/asset/public-avatars', method: 'get', params: { pageNum, pageSize } })
}

export function listAdminPublicAvatars(pageNum = 1, pageSize = 20, status?: string): Promise<AjaxResult<{ items: AvatarCatalogItem[]; total: number }>> {
  return request({ url: '/system/asset/admin/public-avatars', method: 'get', params: { pageNum, pageSize, status } })
}

export function getAvatarDetail(avatarId: string): Promise<AjaxResult<AvatarDetail>> {
  return request({ url: `/system/asset/avatars/${avatarId}`, method: 'get' })
}

export function unpublishOfficialAvatar(avatarId: string, reason: string): Promise<AjaxResult> {
  return request({ url: `/system/asset/admin/avatars/${avatarId}/unpublish`, method: 'post', data: { reason } })
}

export function disableOfficialAvatar(avatarId: string, reason: string): Promise<AjaxResult> {
  return request({ url: `/system/asset/admin/avatars/${avatarId}/disable`, method: 'post', data: { reason } })
}
