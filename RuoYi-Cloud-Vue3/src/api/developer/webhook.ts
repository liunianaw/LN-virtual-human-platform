import request from '@/utils/request'
import type { AjaxResult } from '@/types'
import type { Page } from './usage'

export interface WebhookEndpoint {
  endpointId: string; name: string; url: string; status: 'ACTIVE' | 'DISABLED'
  events: string[]; timeoutMs: number; maxAttempts: number; revision: number
  signingSecret?: string | null; secretAvailable?: boolean
}
export interface WebhookDelivery {
  deliveryId: string; endpointId: string; eventId: string; taskId: string; eventType: string
  status: string; attemptCount: number; lastHttpStatus?: number; lastErrorCode?: string
  nextAttemptAt?: string; deliveredAt?: string; createdAt: string
}
export interface WebhookAttempt { attemptId: string; attemptNo: number; httpStatus?: number; errorCode?: string; latencyMs?: number; startedAt: string }
const base = '/system/api/v1/developer/webhook-endpoints'
const headers = (revision?: number) => ({ 'Idempotency-Key': crypto.randomUUID(), ...(revision ? { 'If-Match': String(revision) } : {}) })
export const listWebhooks = (pageNum = 1, pageSize = 20): Promise<AjaxResult<Page<WebhookEndpoint>>> => request({ url: base, method: 'get', params: { pageNum, pageSize } })
export const createWebhook = (data: { name: string; url: string; events: string[] }): Promise<AjaxResult<WebhookEndpoint>> => request({ url: base, method: 'post', data, headers: headers() })
export const rotateWebhook = (id: string, revision: number): Promise<AjaxResult<WebhookEndpoint>> => request({ url: `${base}/${id}/secret`, method: 'post', headers: headers(revision) })
export const setWebhookStatus = (id: string, revision: number, status: 'ACTIVE' | 'DISABLED'): Promise<AjaxResult<WebhookEndpoint>> => request({ url: `${base}/${id}/status`, method: 'post', data: { status }, headers: headers(revision) })
export const listDeliveries = (id: string, pageNum = 1, from?: string, to?: string): Promise<AjaxResult<Page<WebhookDelivery>>> => request({ url: `${base}/${id}/deliveries`, method: 'get', params: { pageNum, pageSize: 20, from, to } })
export const listAttempts = (id: string, pageNum = 1, from?: string, to?: string): Promise<AjaxResult<Page<WebhookAttempt>>> => request({ url: `${base}/deliveries/${id}/attempts`, method: 'get', params: { pageNum, pageSize: 20, from, to } })
