import { createRequestId } from '@ln-avatar/sdk'
import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface Page<T> { items: T[]; total: number; pageNum: number; pageSize: number }
export interface GenerationTask { taskId: string; accountId: string; status: string; internalState: string; progress: number; errorCode?: string; createdAt: string; unknown_attempts?: number }
export interface Attempt { attemptId: string; stepId: string; status: string; providerRequestId?: string; providerTaskId?: string; errorCode?: string; etag: string; allowedOperations: string[] }
export interface CallRecord { callId: string; accountId: string; operationKey: string; factKind?: 'LOGICAL' | 'ATTEMPT'; capability: string; status: string; usageAvailable: boolean; costAmount?: string | null; currency?: string | null; costSource: string; errorCode?: string; etag: string; createdAt: string }
const headers = (etag: string) => ({ 'If-Match': etag, 'Idempotency-Key': createRequestId(), repeatSubmit: false })

export const listTasks = (params: Record<string, unknown>): Promise<AjaxResult<Page<GenerationTask>>> => request({ url: '/api/v1/admin/generation-tasks', method: 'get', params })
export const getTask = (taskId: string): Promise<AjaxResult<{ task: GenerationTask; attempts: Attempt[]; allowedOperations: string[] }>> => request({ url: `/api/v1/admin/generation-tasks/${taskId}`, method: 'get' })
export const reconcileAttempt = (taskId: string, attempt: Attempt, reason: string): Promise<AjaxResult<{ operationId: string; status: string }>> => request({ url: `/api/v1/admin/generation-tasks/${taskId}/attempts/${attempt.attemptId}/reconciliations`, method: 'post', data: { reason }, headers: headers(attempt.etag) })
export const listCalls = (params: Record<string, unknown>): Promise<AjaxResult<Page<CallRecord>>> => request({ url: '/api/v1/admin/call-records', method: 'get', params })
export const reviewCall = (call: CallRecord, data: { reviewedStatus: string; evidenceNote: string; costAmount?: string; currency?: string; costSource?: string }): Promise<AjaxResult<{ operationId: string; status: string }>> => request({ url: `/api/v1/admin/call-records/${call.callId}/reviews`, method: 'post', data, headers: headers(call.etag) })
