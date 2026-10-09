import { createRequestId } from '@ln-avatar/sdk'
import request from '@/utils/request'
import type { AjaxResult } from '@/types'
import type { PageResult, PageQuery } from '@/types/page'

export interface OfficialService { serviceId: string; name: string; capability: 'AVATAR_GENERATION' | 'TTS' | 'ASR'; providerCode: string; endpoint: string; modelId: string; parameters: Record<string, unknown>; status: 'ACTIVE' | 'DISABLED'; revision: string; credentialConfigured: boolean; secretId?: string }
export interface OfficialServiceInput { name: string; capability: OfficialService['capability']; providerCode: string; endpoint: string; modelId: string; parameters: Record<string, unknown>; secretId?: string }
const headers = (revision?: string) => ({ 'Idempotency-Key': createRequestId(), ...(revision ? { 'If-Match': revision } : {}) })
export const listOfficialServices = (params: PageQuery & { capability?: string; status?: string } = {}): Promise<AjaxResult<PageResult<OfficialService>>> => request({ url: '/api/v1/admin/official-services', method: 'get', params })
export const createOfficialService = (data: OfficialServiceInput): Promise<AjaxResult<OfficialService>> => request({ url: '/api/v1/admin/official-services', method: 'post', data, headers: headers() })
export const updateOfficialService = (id: string, revision: string, data: OfficialServiceInput): Promise<AjaxResult<OfficialService>> => request({ url: `/api/v1/admin/official-services/${id}`, method: 'put', data, headers: headers(revision) })
export const replaceOfficialServiceCredential = (id: string, revision: string, providerKey: string): Promise<AjaxResult<OfficialService>> => request({ url: `/api/v1/admin/official-services/${id}/credential`, method: 'put', data: { providerKey }, headers: headers(revision) })
export const checkOfficialService = (id: string, revision: string): Promise<AjaxResult<{ configurationValid: boolean; issues: string[] }>> => request({ url: `/api/v1/admin/official-services/${id}/checks`, method: 'post', data: {}, headers: headers(revision) })
export const setOfficialServiceStatus = (id: string, revision: string, status: OfficialService['status'], reason: string): Promise<AjaxResult<OfficialService>> => request({ url: `/api/v1/admin/official-services/${id}/status`, method: 'post', data: { status, reason }, headers: headers(revision) })
