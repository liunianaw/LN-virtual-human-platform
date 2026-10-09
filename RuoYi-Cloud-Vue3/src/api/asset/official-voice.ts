import { createRequestId } from '@ln-avatar/sdk'
import request from '@/utils/request'
import type { AjaxResult } from '@/types'
import type { PageResult, PageQuery } from '@/types/page'

export interface VoiceCapability { providerType: string; modelId: string; modelRevision: string; capabilityVersion: string; languages: string[]; voices: { id: string; displayName: string; languages: string[] }[]; parameters: Record<string, { minimum: number; maximum: number; defaultValue: number }>; referenceVoice: boolean; referenceMaxDurationMs?: number; fallbackTarget: boolean }
export interface VoiceServiceHealth { serviceId: string; name: string; providerType: string; configValid: boolean; apiReachable: boolean; modelReady: boolean; status: string; lastSynthesisStatus: string }
export interface VoiceReadiness { apiReachable: boolean; modelReady: boolean; services: VoiceServiceHealth[] }
export interface VoiceTask { taskId: string; purpose: string; status: string; errorCode?: string; winnerAttemptId?: string; settlement?: string; factDeliveryReview?: boolean; degraded?: boolean; attempts: { attemptId: string; providerType: string; state: string; reasonCode: string; costSource: string }[] }
export interface OfficialVoiceService { capability: VoiceCapability; serviceId: string; name: string; modelId: string; revision: string; availableVoiceAliases: string[] }
export interface OfficialVoiceVersion { versionId: string; versionNo: number; officialServiceId: string; voiceAlias: string; language?: string; modelId?: string; parameters: Record<string, number>; providerType?: string; modelRevision?: string; capabilityVersion?: string; referenceAssetId?: string; referenceText?: string; fallbackVoiceVersionId?: string; allowVoiceChange?: boolean }
export interface OfficialVoice { voiceId: string; name: string; description?: string; status: string; currentVersionId?: string; revision: string; versions: OfficialVoiceVersion[] }
export interface OfficialVoiceSummary { voiceId: string; name: string; description?: string; status: string; currentVersionId?: string; revision: string; voiceAlias?: string }
export interface OfficialVoiceInput { name: string; description?: string; officialServiceId: string; expectedServiceRevision: string; voiceAlias: string; language?: string; parameters: Record<string, number>; fallbackVoiceVersionId?: string; allowVoiceChange?: boolean; referenceAssetId?: string; referenceText?: string }

const headers = (extra: Record<string, string> = {}) => ({ ...extra, 'Idempotency-Key': createRequestId() })
export const listOfficialVoiceServices = (): Promise<AjaxResult<OfficialVoiceService[]>> => request({ url: '/api/v1/admin/public-voices/services', method: 'get' })
export const listOfficialVoices = (params: PageQuery & { status?: string } = {}): Promise<AjaxResult<PageResult<OfficialVoiceSummary>>> => request({ url: '/api/v1/admin/public-voices', method: 'get', params })
export const getOfficialVoice = (voiceId: string): Promise<AjaxResult<OfficialVoice>> => request({ url: `/api/v1/admin/public-voices/${voiceId}`, method: 'get' })
export const createOfficialVoice = (data: OfficialVoiceInput): Promise<AjaxResult<OfficialVoice>> => request({ url: '/api/v1/admin/public-voices', method: 'post', data, headers: headers() })
export const createOfficialVoiceVersion = (voiceId: string, revision: string, data: OfficialVoiceInput): Promise<AjaxResult<OfficialVoice>> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions`, method: 'post', data, headers: headers({ 'If-Match': revision }) })
export const publishOfficialVoice = (voiceId: string, versionId: string, revision: string): Promise<AjaxResult> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions/${versionId}/publish`, method: 'post', data: { auditionConfirmed: true }, headers: headers({ 'If-Match': revision }) })
export const auditionOfficialVoice = (voiceId: string, versionId: string, text: string, key: string): Promise<Blob> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions/${versionId}/audition`, method: 'post', data: { text }, headers: { 'Idempotency-Key': key }, responseType: 'blob', timeout: 70000 })

export const voiceCapabilities = (): Promise<AjaxResult<VoiceCapability[]>> => request({ url: '/api/v1/admin/public-voices/capabilities', method: 'get' })
export const voiceReadiness = (): Promise<AjaxResult<VoiceReadiness>> => request({ url: '/api/v1/admin/public-voices/readiness', method: 'get' })
export const voiceTasks = (): Promise<AjaxResult<VoiceTask[]>> => request({ url: '/api/v1/admin/public-voices/tasks', method: 'get' })
export const pageVoiceTasks = (params: PageQuery & { taskId?: string; status?: string }): Promise<AjaxResult<PageResult<VoiceTask>>> => request({ url: '/api/v1/admin/public-voices/tasks', method: 'get', params })
export const voiceReferences = (): Promise<AjaxResult<{ id: string; name: string }[]>> => request({ url: '/api/v1/admin/public-voices/references', method: 'get' })
export const uploadVoiceReference = (file: File): Promise<AjaxResult<{ referenceAssetId: string }>> => { const data = new FormData(); data.append('file', file); return request({ url: '/api/v1/admin/public-voices/references', method: 'post', data }) }
