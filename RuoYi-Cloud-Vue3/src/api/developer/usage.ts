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
  costAmount?: number; currency?: string; pointAmount?: number; pointState?: string; errorCode?: string; createdAt: string
}
export interface QuotaBalance { quotaType: string; grantedUnits: number; usedUnits: number; reservedUnits: number; availableUnits: number }
export interface QuotaReservation { reservationId: string; quotaType: string; businessType: string; businessId: string; state: string; reservedUnits: number; settledUnits?: number; billingItem?: string; measuredUnits?: number; unitPrice?: number; createdAt: string }
export interface UsageLimits { configured: boolean; limits: Record<string, number>; balances: QuotaBalance[] }
export interface CapabilityUsage {
  capability: string; requestCount: number; successCount: number; failureCount: number; unknownCount: number
  imageCount: number; inputChars: number; audioDurationMs: number
}
export interface AdminUsageOverview {
  from: string; to: string; totalUsers: number; activeUsers: number; pointAccounts: number; usageUsers: number
  grantedPoints: number; usedPoints: number; reservedPoints: number; availablePoints: number
  requestCount: number; successCount: number; failureCount: number; unknownCount: number
  imageCount: number; inputChars: number; audioDurationMs: number; capabilities: CapabilityUsage[]
}
export interface AdminAccountUsageRow {
  userId: string; userName: string; nickName: string; email: string; status: string; createdAt: string
  grantedPoints: number; usedPoints: number; reservedPoints: number; availablePoints: number
  requestCount: number; successCount: number; failureCount: number; unknownCount: number; lastCallAt?: string
}
export interface AdminAccountUsageDetail {
  account: AdminAccountUsageRow; from: string; to: string; summary: AdminAccountUsageRow
  capabilities: CapabilityUsage[]; quota: UsageLimits; recentCalls: CallRecord[]; recentReservations: QuotaReservation[]
}
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
export interface PointRate { id: string; versionNo: number; generationActionPoints: number; ttsCharacterPoints: number; storageBytePoints: number; effectiveAt: string; createdAt?: string }
export interface PointRateInput { generationActionPoints: number; ttsCharacterPoints: number; storageBytePoints: number; effectiveAt?: string }
export const getPointRates = (): Promise<AjaxResult<{ current: PointRate; items: PointRate[] }>> => request({ url: '/system/api/v1/admin/point-rates', method: 'get' })
export const publishPointRate = (data: PointRateInput): Promise<AjaxResult<PointRate>> => request({ url: '/system/api/v1/admin/point-rates', method: 'post', data })
export const grantAccountPoints = (accountId: string, points: number, reason: string, key: string): Promise<AjaxResult<{ applied: boolean }>> => request({ url: `${adminBase(accountId)}/point-grants`, method: 'post', data: { points, reason }, headers: { 'Idempotency-Key': key, repeatSubmit: false } })
export const getAdminUsageOverview = (params: { from: string; to: string }): Promise<AjaxResult<AdminUsageOverview>> => request({ url: '/system/api/v1/admin/usage/overview', method: 'get', params })
export const getAdminUsageAccounts = (params: { keyword?: string; from: string; to: string; pageNum: number; pageSize: number }): Promise<AjaxResult<Page<AdminAccountUsageRow>>> => request({ url: '/system/api/v1/admin/usage/accounts', method: 'get', params })
export const getAdminAccountUsage = (accountId: string, params: { from: string; to: string }): Promise<AjaxResult<AdminAccountUsageDetail>> => request({ url: `${adminBase(accountId)}/usage`, method: 'get', params })
