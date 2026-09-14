import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface AvatarReferenceFile {
  fileId: number
  contentType?: string
  sizeBytes?: number
  width?: number
  height?: number
  readUrl?: string
}

export interface CreateAvatarGenerationTaskRequest {
  sourceFileId: number
  officialServiceId: number
  requestId: string
  name: string
}

export interface AvatarGenerationService {
  serviceId: number
  name: string
  providerCode: string
  modelId: string
  revision: number
}

export interface AvatarGenerationTask {
  taskId: number
  avatarId: number
  avatarVersionId: number
  sourceFileId: number
  requestId: string
  status: string
  internalState?: string
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
  avatarId: number
  versionId: number
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

export function getAvatarGenerationTask(taskId: number): Promise<AjaxResult<AvatarGenerationTask>> {
  return request({
    url: '/system/asset/generation-tasks/' + taskId,
    method: 'get'
  })
}

export function getAvatarVersionPreview(avatarId: number, versionId: number): Promise<AjaxResult<AvatarVersionPreview>> {
  return request({
    url: '/system/asset/avatars/' + avatarId + '/versions/' + versionId + '/preview',
    method: 'get'
  })
}

export function publishAvatarVersion(
  avatarId: number,
  versionId: number,
  data: PublishAvatarVersionRequest
): Promise<AjaxResult<AvatarVersionPreview>> {
  return request({
    url: '/system/asset/avatars/' + avatarId + '/versions/' + versionId + '/publish',
    method: 'post',
    data
  })
}
