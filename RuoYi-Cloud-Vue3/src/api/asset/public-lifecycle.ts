import { createRequestId } from '@ln-avatar/sdk'
import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export type PublicAssetKind = 'avatars' | 'voices'
export interface PublicAssetReferences { counts: { applications: number; sessions: number; generations: number }; items: { holderType: string; holderId: string; state: string }[]; total: number }
export interface LifecycleResult { resourceId: string; status: string; revision: string; etag: string }
const headers = (revision: string) => ({ 'If-Match': revision, 'Idempotency-Key': createRequestId() })

export const getReferences = (kind: PublicAssetKind, id: string): Promise<AjaxResult<PublicAssetReferences>> => request({ url: `/api/v1/admin/public-${kind}/${id}/references`, method: 'get' })
export const unpublish = (kind: PublicAssetKind, id: string, revision: string, reason: string): Promise<AjaxResult<LifecycleResult>> => request({ url: `/api/v1/admin/public-${kind}/${id}/unpublish`, method: 'post', data: { reason }, headers: headers(revision) })
export const disable = (kind: PublicAssetKind, id: string, revision: string, reason: string): Promise<AjaxResult<LifecycleResult>> => request({ url: `/api/v1/admin/public-${kind}/${id}/disable`, method: 'post', data: { reason, acknowledgeImpact: true }, headers: headers(revision) })
export const requestDelete = (kind: PublicAssetKind, id: string, revision: string): Promise<AjaxResult<LifecycleResult>> => request({ url: `/api/v1/admin/public-${kind}/${id}`, method: 'delete', headers: headers(revision) })
export const retryCleanup = (kind: PublicAssetKind, id: string, revision: string): Promise<AjaxResult<LifecycleResult>> => request({ url: `/api/v1/admin/public-${kind}/${id}/cleanup-retries`, method: 'post', data: {}, headers: headers(revision) })
