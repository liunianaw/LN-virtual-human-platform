import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface AccessKeySummary { keyId: string; name: string; keyType: 'MANAGEMENT' | 'APPLICATION'; applicationId?: string; publicId: string; displaySuffix: string; status: 'ACTIVE' | 'DISABLED' | 'DELETED'; createdAt: string; lastUsedAt?: string }
export interface IssuedAccessKey extends AccessKeySummary { secret?: string }
const headers = () => ({ 'Idempotency-Key': crypto.randomUUID() })
export const listManagementKeys = (): Promise<AjaxResult<AccessKeySummary[]>> => request({ url: '/api/v1/developer/access-keys', method: 'get' })
export const createManagementKey = (name: string, scopes: string[]): Promise<AjaxResult<IssuedAccessKey>> => request({ url: '/api/v1/developer/access-keys', method: 'post', data: { name, scopes }, headers: headers() })
export const rotateManagementKey = (keyId: string): Promise<AjaxResult<IssuedAccessKey>> => request({ url: `/api/v1/developer/access-keys/${keyId}/rotations`, method: 'post', headers: headers() })
export const changeManagementKey = (keyId: string, action: 'disable' | 'delete'): Promise<AjaxResult<AccessKeySummary>> => request({ url: `/api/v1/developer/access-keys/${keyId}/${action}`, method: 'post', headers: headers() })
export const listApplicationSecrets = (applicationId: string): Promise<AjaxResult<AccessKeySummary[]>> => request({ url: `/api/v1/applications/${applicationId}/secret`, method: 'get' })
export const resetApplicationSecret = (applicationId: string, name: string): Promise<AjaxResult<IssuedAccessKey>> => request({ url: `/api/v1/applications/${applicationId}/secret/reset`, method: 'post', data: { name }, headers: headers() })
export const changeApplicationSecret = (applicationId: string, keyId: string, action: 'disable' | 'delete'): Promise<AjaxResult<AccessKeySummary>> => request({ url: `/api/v1/applications/${applicationId}/secret/${keyId}/${action}`, method: 'post', headers: headers() })
