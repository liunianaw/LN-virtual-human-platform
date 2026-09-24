import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface ApplicationSummary { applicationId: string; name: string; description?: string; status: 'ACTIVE' | 'DISABLED'; adminDisabled: number; configStatus: 'UNCONFIGURED' | 'CONFIGURED'; currentConfigVersionId?: string; revision: string }
export interface ApplicationConfig { configVersionId: string; versionNo: number; mode: string; avatarVersionId: string; voiceVersionId: string; configHash: string; contextPolicy?: Record<string, unknown>; runtimeLimits?: Record<string, number> }
export interface ApplicationDetail extends ApplicationSummary { versions: ApplicationConfig[]; currentConfig?: ApplicationConfig }
export interface ApplicationChoices { avatars: Array<{ versionId: string; avatarId: string; name: string; versionNo: number; visibility: string }>; voices: Array<{ versionId: string; voiceId: string; name: string; versionNo: number; voiceAlias: string }> }

const commandHeaders = (revision?: string) => ({ 'Idempotency-Key': crypto.randomUUID(), ...(revision ? { 'If-Match': revision } : {}) })
export const listApplications = (): Promise<AjaxResult<{ items: ApplicationSummary[] }>> => request({ url: '/api/v1/applications', method: 'get' })
export const getApplication = (applicationId: string): Promise<AjaxResult<ApplicationDetail>> => request({ url: `/api/v1/applications/${applicationId}`, method: 'get' })
export const listApplicationChoices = (): Promise<AjaxResult<ApplicationChoices>> => request({ url: '/api/v1/applications/resources', method: 'get' })
export const createApplication = (data: { name: string; description?: string }): Promise<AjaxResult<ApplicationSummary>> => request({ url: '/api/v1/applications', method: 'post', data, headers: commandHeaders() })
export const publishApplicationConfig = (applicationId: string, revision: string, data: { mode: string; avatarVersionId: string; voiceVersionId: string; contextPolicy: { enabled: false } }): Promise<AjaxResult<ApplicationConfig>> => request({ url: `/api/v1/applications/${applicationId}/config-versions`, method: 'post', data, headers: commandHeaders(revision) })
export const changeApplicationStatus = (applicationId: string, revision: string, status: 'ACTIVE' | 'DISABLED', reason: string): Promise<AjaxResult> => request({ url: `/api/v1/applications/${applicationId}/status`, method: 'post', data: { status, reason }, headers: commandHeaders(revision) })
export const createApplicationDebugSession = (applicationId: string): Promise<AjaxResult<{ sessionId: string }>> => request({ url: `/api/v1/applications/${applicationId}/debug-sessions`, method: 'post', headers: commandHeaders() })
