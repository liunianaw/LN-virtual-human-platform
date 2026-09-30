import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface Page<T> { items: T[]; total: number; pageNum: number; pageSize: number }
export interface UsageDaily {
  applicationId: string; usageDate: string; capability: string; billingOwner: string; currency: string
  requestCount: number; successCount: number; failureCount: number; unknownCount: number
  inputTokens: number; outputTokens: number; inputChars: number; imageCount: number
  knownUsageCount: number; knownCostCount: number; costAmount: number
}
export interface CallRecord {
  callId: string; applicationId?: string; capability: string; status: string; billingOwner: string
  usageAvailable: number; inputTokens?: number; outputTokens?: number; inputChars?: number
  costAmount?: number; currency?: string; errorCode?: string; createdAt: string
}
export interface QuotaBalance { quotaType: string; grantedUnits: number; usedUnits: number; reservedUnits: number; availableUnits: number }
export interface QuotaReservation { reservationId: string; quotaType: string; businessType: string; businessId: string; state: string; reservedUnits: number; createdAt: string }
export interface UsageLimits { configured: boolean; limits: Record<string, number>; balances: QuotaBalance[] }
export interface AccountLimitInput { revision: number; maxFileBytes: number; maxSessions: number; maxGenerationTasks: number; maxTurns: number }
export interface QuotaGrantInput { quotaType: 'AVATAR_COUNT' | 'TTS_CHAR' | 'STORAGE_BYTE'; units: number; reason: string }
const base = '/system/api/v1/developer/usage'
export const getUsage = (params: Record<string, string | number | undefined>): Promise<AjaxResult<Page<UsageDaily>>> => request({ url: base, method: 'get', params })
export const getCalls = (params: Record<string, string | number | undefined>): Promise<AjaxResult<Page<CallRecord>>> => request({ url: '/system/api/v1/developer/call-records', method: 'get', params })
export const getLimits = (): Promise<AjaxResult<UsageLimits>> => request({ url: `${base}/limits`, method: 'get' })
export const getReservations = (params: Record<string, string | number | undefined>): Promise<AjaxResult<Page<QuotaReservation>>> => request({ url: `${base}/reservations`, method: 'get', params })
const adminBase = (accountId: string) => `/system/api/v1/admin/accounts/${accountId}`
export const getAccountQuotas = (accountId: string): Promise<AjaxResult<UsageLimits>> => request({ url: `${adminBase(accountId)}/quotas`, method: 'get' })
export const saveAccountLimits = (accountId: string, data: AccountLimitInput): Promise<AjaxResult<UsageLimits>> => request({ url: `${adminBase(accountId)}/limits`, method: 'put', data })
export const grantAccountQuota = (accountId: string, data: QuotaGrantInput, key: string): Promise<AjaxResult<{ applied: boolean; balances: QuotaBalance[] }>> => request({ url: `${adminBase(accountId)}/quota-grants`, method: 'post', data, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
