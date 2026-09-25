import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export type RelayCapability = 'llm' | 'asr' | 'image' | 'tool' | 'cancel'
export interface RelayVersionInput {
  baseUrl: string
  protocolVersion: '1'
  capabilities: Partial<Record<RelayCapability, boolean>>
  timeoutMs: number
  maxResponseBytes: number
}
export interface RelayVersion extends RelayVersionInput { versionId: string; versionNo: number; endpoints: Record<string, string>; createdAt: string }
export interface RelaySummary {
  relayId: string; name: string; description?: string; status: 'ACTIVE' | 'DISABLED'
  currentVersionId: string; grantMode: 'ALL_ACCOUNT_APPS' | 'EXPLICIT_APPS'; authEpoch: string
  adminDisabled: number; tokenSuffix?: string; lastTestStatus?: 'SUCCESS' | 'FAILED'; lastTestError?: string
  lastTestVersionId?: string
}
export interface RelayDetail extends RelaySummary {
  versions: RelayVersion[]
  grants: Array<{ applicationId: string; scopes: Array<'LLM' | 'ASR'>; status: string }>
}
export interface RelayCreate {
  name: string; description?: string; accessToken: string
  grantMode: 'ALL_ACCOUNT_APPS' | 'EXPLICIT_APPS'; version: RelayVersionInput
}
const base = '/api/v1/developer/relay-services'
const commandHeaders = (epoch?: string) => ({ 'Idempotency-Key': crypto.randomUUID(), ...(epoch ? { 'If-Match': epoch } : {}) })

export const listRelays = (): Promise<AjaxResult<{ items: RelaySummary[] }>> => request({ url: base, method: 'get' })
export const getRelay = (id: string): Promise<AjaxResult<RelayDetail>> => request({ url: `${base}/${id}`, method: 'get' })
export const createRelay = (data: RelayCreate): Promise<AjaxResult<RelayDetail>> => request({ url: base, method: 'post', data, headers: commandHeaders() })
export const addRelayVersion = (id: string, epoch: string, data: RelayVersionInput): Promise<AjaxResult<RelayDetail>> => request({ url: `${base}/${id}/versions`, method: 'post', data, headers: commandHeaders(epoch) })
export const setRelayGrants = (id: string, epoch: string, data: { grantMode: 'ALL_ACCOUNT_APPS' | 'EXPLICIT_APPS'; grants: Array<{ applicationId: string; scopes: Array<'LLM' | 'ASR'> }> }): Promise<AjaxResult<RelayDetail>> => request({ url: `${base}/${id}/grants`, method: 'put', data, headers: commandHeaders(epoch) })
export const rotateRelayToken = (id: string, epoch: string, accessToken: string): Promise<AjaxResult<RelayDetail>> => request({ url: `${base}/${id}/token`, method: 'post', data: { accessToken }, headers: commandHeaders(epoch) })
export const testRelay = (id: string): Promise<AjaxResult<{ success: boolean; errorCode?: string }>> => request({ url: `${base}/${id}/connection-test`, method: 'post', headers: commandHeaders() })
export const changeRelayStatus = (id: string, epoch: string, status: 'ACTIVE' | 'DISABLED', reason: string): Promise<AjaxResult<RelayDetail>> => request({ url: `${base}/${id}/status`, method: 'post', data: { status, reason }, headers: commandHeaders(epoch) })
export const deleteRelay = (id: string, epoch: string): Promise<AjaxResult> => request({ url: `${base}/${id}`, method: 'delete', headers: commandHeaders(epoch) })
