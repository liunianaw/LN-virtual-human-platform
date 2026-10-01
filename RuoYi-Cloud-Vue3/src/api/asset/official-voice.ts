import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface OfficialVoiceService { serviceId: string; name: string; modelId: string; revision: string; availableVoiceAliases: string[] }
export interface OfficialVoiceVersion { versionId: string; versionNo: number; officialServiceId: string; voiceAlias: string; language?: string; modelId?: string; parameters: Record<string, number> }
export interface OfficialVoice { voiceId: string; name: string; description?: string; status: string; currentVersionId?: string; revision: string; versions: OfficialVoiceVersion[] }
export interface OfficialVoiceSummary { voiceId: string; name: string; description?: string; status: string; currentVersionId?: string; revision: string; voiceAlias?: string }
export interface OfficialVoiceInput { name: string; description?: string; officialServiceId: string; expectedServiceRevision: string; voiceAlias: string; language?: string; parameters: Record<string, number> }

const headers = (extra: Record<string, string> = {}) => ({ ...extra, 'Idempotency-Key': crypto.randomUUID() })
export const listOfficialVoiceServices = (): Promise<AjaxResult<OfficialVoiceService[]>> => request({ url: '/api/v1/admin/public-voices/services', method: 'get' })
export const listOfficialVoices = (): Promise<AjaxResult<{ items: OfficialVoiceSummary[] }>> => request({ url: '/api/v1/admin/public-voices', method: 'get' })
export const getOfficialVoice = (voiceId: string): Promise<AjaxResult<OfficialVoice>> => request({ url: `/api/v1/admin/public-voices/${voiceId}`, method: 'get' })
export const createOfficialVoice = (data: OfficialVoiceInput): Promise<AjaxResult<OfficialVoice>> => request({ url: '/api/v1/admin/public-voices', method: 'post', data, headers: headers() })
export const createOfficialVoiceVersion = (voiceId: string, revision: string, data: OfficialVoiceInput): Promise<AjaxResult<OfficialVoice>> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions`, method: 'post', data, headers: headers({ 'If-Match': revision }) })
export const publishOfficialVoice = (voiceId: string, versionId: string, revision: string): Promise<AjaxResult> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions/${versionId}/publish`, method: 'post', data: { auditionConfirmed: true }, headers: headers({ 'If-Match': revision }) })
export const auditionOfficialVoice = (voiceId: string, versionId: string, text: string): Promise<Blob> => request({ url: `/api/v1/admin/public-voices/${voiceId}/versions/${versionId}/audition`, method: 'post', data: { text }, headers: headers(), responseType: 'blob', timeout: 70000 })
