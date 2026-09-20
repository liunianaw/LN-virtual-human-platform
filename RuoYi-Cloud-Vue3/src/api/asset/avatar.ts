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
