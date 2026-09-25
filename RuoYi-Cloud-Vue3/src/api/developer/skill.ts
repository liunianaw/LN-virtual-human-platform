import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface SkillVersionInput {
  skillType: 'PROMPT' | 'HTTP_TOOL'
  toolName?: string
  instructions?: string
  contextRequirements: Record<string, boolean>
  toolUrl?: string
  httpMethod?: 'GET' | 'POST'
  inputSchema?: object
  outputSchema?: object
  accessToken?: string
  requiresUserCredential?: boolean
  identityBinding?: Record<string, 'HEADER'>
  frontendFields?: string[]
  timeoutMs?: number
  maxResultBytes?: number
  maxCallsPerTurn?: number
  importFormat?: 'JSON'
}
export interface SkillSummary {
  skillId: string; name: string; description?: string; visibility: 'OFFICIAL' | 'PRIVATE'
  status: 'PUBLISHED' | 'UNLISTED' | 'DISABLED' | 'DRAFT'
  currentVersionId: string; revision: string
}
export interface SkillVersion extends SkillVersionInput { versionId: string; versionNo: number; tokenSuffix?: string }
export interface SkillDetail extends SkillSummary { versions: SkillVersion[]; referenceCount: number }
const base = '/api/v1/developer/skills'
const headers = (revision?: string) => ({ 'Idempotency-Key': crypto.randomUUID(), ...(revision ? { 'If-Match': revision } : {}) })

export const listSkills = (): Promise<AjaxResult<{ items: SkillSummary[] }>> => request({ url: base, method: 'get' })
export const skillCandidates = (): Promise<AjaxResult<{ items: SkillSummary[] }>> => request({ url: `${base}/candidates`, method: 'get' })
export const getSkill = (id: string): Promise<AjaxResult<SkillDetail>> => request({ url: `${base}/${id}`, method: 'get' })
export const createSkill = (data: { name: string; description?: string; version: SkillVersionInput }): Promise<AjaxResult<SkillDetail>> => request({ url: base, method: 'post', data, headers: headers() })
export const createOfficialSkill = (data: { name: string; description?: string; version: SkillVersionInput }): Promise<AjaxResult<SkillDetail>> => request({ url: `${base}/official`, method: 'post', data, headers: headers() })
export const addSkillVersion = (id: string, revision: string, data: SkillVersionInput): Promise<AjaxResult<SkillDetail>> => request({ url: `${base}/${id}/versions`, method: 'post', data, headers: headers(revision) })
export const changeSkillStatus = (id: string, revision: string, status: SkillSummary['status'], reason: string): Promise<AjaxResult<SkillDetail>> => request({ url: `${base}/${id}/status`, method: 'post', data: { status, reason }, headers: headers(revision) })
export const checkSkillConnection = (id: string): Promise<AjaxResult<{ success: boolean; checked?: string; errorCode?: string }>> => request({ url: `${base}/${id}/connection-check`, method: 'post' })
export const deleteSkill = (id: string, revision: string): Promise<AjaxResult> => request({ url: `${base}/${id}`, method: 'delete', headers: headers(revision) })
