import request from '@/utils/request'
import type { AjaxResult } from '@/types'
import type { PageResult, PageQuery } from '@/types/page'

export interface ApplicationSkillBinding { skillId: string; sortOrder: number; name?: string; skillType?: string; toolName?: string }
export interface ApplicationSummary {
  applicationId: string; name: string; description?: string; avatarId?: string; voiceId?: string
  systemPrompt?: string; status: 'ACTIVE' | 'DISABLED'; revision: string
}
export interface ApplicationDetail extends ApplicationSummary { skills: ApplicationSkillBinding[] }
export interface ApplicationChoices {
  avatars: Array<{ avatarId: string; name: string; visibility: string }>
  voices: Array<{ voiceId: string; name: string; voiceAlias: string }>
  skills: Array<{ skillId: string; name: string; skillType: string; toolName?: string; visibility: string }>
}
export interface ApplicationInput {
  name: string; description?: string; avatarId: string; voiceId: string; systemPrompt?: string
  skills: ApplicationSkillBinding[]
}
export interface ApplicationSecretSummary {
  keyId: string; name: string; displaySuffix: string; status: 'ACTIVE' | 'DISABLED' | 'DELETED'; secret?: string
}

const commandHeaders = (revision?: string) => ({
  'Idempotency-Key': crypto.randomUUID(), ...(revision ? { 'If-Match': revision } : {})
})

export const listApplications = (params: PageQuery & { status?: string; keyword?: string } = {}): Promise<AjaxResult<PageResult<ApplicationSummary>>> =>
  request({ url: '/api/v1/applications', method: 'get', params })
export const getApplication = (applicationId: string): Promise<AjaxResult<ApplicationDetail>> =>
  request({ url: `/api/v1/applications/${applicationId}`, method: 'get' })
export const listApplicationChoices = (applicationId: string): Promise<AjaxResult<ApplicationChoices>> =>
  request({ url: `/api/v1/applications/${applicationId}/resources`, method: 'get' })
export const createApplication = (data: { name: string; description?: string }): Promise<AjaxResult<ApplicationSummary>> =>
  request({ url: '/api/v1/applications', method: 'post', data, headers: commandHeaders() })
export const updateApplication = (applicationId: string, revision: string, data: ApplicationInput): Promise<AjaxResult<ApplicationDetail>> =>
  request({ url: `/api/v1/applications/${applicationId}`, method: 'put', data, headers: commandHeaders(revision) })
export const changeApplicationStatus = (applicationId: string, revision: string, status: 'ACTIVE' | 'DISABLED', reason: string): Promise<AjaxResult> =>
  request({ url: `/api/v1/applications/${applicationId}/status`, method: 'post', data: { status, reason }, headers: commandHeaders(revision) })
export const listApplicationSecrets = (applicationId: string): Promise<AjaxResult<ApplicationSecretSummary[]>> =>
  request({ url: `/api/v1/applications/${applicationId}/secret`, method: 'get' })
export const resetApplicationSecret = (applicationId: string, name: string): Promise<AjaxResult<ApplicationSecretSummary>> =>
  request({ url: `/api/v1/applications/${applicationId}/secret/reset`, method: 'post', data: { name }, headers: commandHeaders() })
export const changeApplicationSecret = (applicationId: string, keyId: string, action: 'disable' | 'delete'): Promise<AjaxResult<ApplicationSecretSummary>> =>
  request({ url: `/api/v1/applications/${applicationId}/secret/${keyId}/${action}`, method: 'post', headers: commandHeaders() })
