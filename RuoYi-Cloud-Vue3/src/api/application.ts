import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface ApplicationSummary { applicationId: string; name: string; description?: string; status: 'ACTIVE' | 'DISABLED'; adminDisabled: number; configStatus: 'UNCONFIGURED' | 'CONFIGURED'; currentConfigVersionId?: string; currentMode?: 'CHAT' | 'SPEAK_ONLY'; revision: string }
export interface ApplicationSkillBinding { skillVersionId: string; enabled: boolean; sortOrder: number }
export interface ApplicationConfig {
  configVersionId: string; versionNo: number; mode: 'CHAT' | 'SPEAK_ONLY'; avatarVersionId: string; voiceVersionId: string
  llmRelayVersionId?: string; asrRelayVersionId?: string; llmModelId?: string; systemPrompt?: string
  llmParameters?: Record<string, unknown> | string; llmCapabilities?: Record<string, boolean> | string
  contextPolicy?: Record<string, unknown> | string; runtimeLimits?: Record<string, number> | string
  skills?: ApplicationSkillBinding[]; configHash: string
}
export interface ApplicationConfigInput {
  mode: 'CHAT' | 'SPEAK_ONLY'; avatarVersionId: string; voiceVersionId: string; llmRelayVersionId?: string
  asrRelayVersionId?: string; llmModelId?: string; systemPrompt?: string
  llmParameters?: Record<string, number>; llmCapabilities?: Record<string, boolean>
  contextPolicy: Record<string, unknown>; runtimeLimits?: Record<string, number>; skills?: ApplicationSkillBinding[]
}
export interface ApplicationDetail extends ApplicationSummary { versions: ApplicationConfig[]; currentConfig?: ApplicationConfig }
export interface ApplicationChoices {
  avatars: Array<{ versionId: string; avatarId: string; name: string; versionNo: number; visibility: string }>
  voices: Array<{ versionId: string; voiceId: string; name: string; versionNo: number; voiceAlias: string }>
  relays: Array<{ versionId: string; name: string; capabilities: string | Record<string, boolean> }>
  skills: Array<{ versionId: string; name: string; skillType: string; toolName?: string }>
}

const commandHeaders = (revision?: string) => ({ 'Idempotency-Key': crypto.randomUUID(), ...(revision ? { 'If-Match': revision } : {}) })
export const listApplications = (): Promise<AjaxResult<{ items: ApplicationSummary[] }>> => request({ url: '/api/v1/applications', method: 'get' })
export const getApplication = (applicationId: string): Promise<AjaxResult<ApplicationDetail>> => request({ url: `/api/v1/applications/${applicationId}`, method: 'get' })
export const listApplicationChoices = (): Promise<AjaxResult<ApplicationChoices>> => request({ url: '/api/v1/applications/resources', method: 'get' })
export const createApplication = (data: { name: string; description?: string }): Promise<AjaxResult<ApplicationSummary>> => request({ url: '/api/v1/applications', method: 'post', data, headers: commandHeaders() })
export const publishApplicationConfig = (applicationId: string, revision: string, data: ApplicationConfigInput): Promise<AjaxResult<ApplicationConfig>> => request({ url: `/api/v1/applications/${applicationId}/config-versions`, method: 'post', data, headers: commandHeaders(revision) })
export const changeApplicationStatus = (applicationId: string, revision: string, status: 'ACTIVE' | 'DISABLED', reason: string): Promise<AjaxResult> => request({ url: `/api/v1/applications/${applicationId}/status`, method: 'post', data: { status, reason }, headers: commandHeaders(revision) })
export const createApplicationDebugSession = (applicationId: string): Promise<{ sessionId: string }> => request({ url: `/api/v1/applications/${applicationId}/debug-sessions`, method: 'post', headers: commandHeaders() })
