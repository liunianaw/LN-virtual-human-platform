import request from '@/utils/request'
import type { AjaxResult } from '@/types'

export interface SkillInput {
  name: string
  description?: string
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
  maxCallsPerSession?: number
  importFormat?: 'JSON'
}
export interface SkillSummary {
  skillId: string
  name: string
  description?: string
  visibility: 'OFFICIAL' | 'PRIVATE'
  status: 'PUBLISHED' | 'UNLISTED' | 'DISABLED' | 'DRAFT'
  skillType: 'PROMPT' | 'HTTP_TOOL'
  toolName?: string
  revision: string
}
export interface SkillDetail extends SkillSummary, Omit<SkillInput, 'name' | 'description'> {
  tokenSuffix?: string
  referenceCount: number
}
const base = (admin: boolean) => admin ? '/api/v1/admin/public-skills' : '/api/v1/developer/skills'
const headers = (revision?: string) => ({
  'Idempotency-Key': crypto.randomUUID(),
  ...(revision ? { 'If-Match': revision } : {})
})
export const listSkills = (admin = false): Promise<AjaxResult<{ items: SkillSummary[] }>> =>
  request({ url: base(admin), method: 'get' })
export const getSkill = (id: string, admin = false): Promise<AjaxResult<SkillDetail>> =>
  request({ url: `${base(admin)}/${id}`, method: 'get' })
export const createSkill = (data: SkillInput, admin = false): Promise<AjaxResult<SkillDetail>> =>
  request({ url: base(admin), method: 'post', data, headers: headers() })
export const updateSkill = (id: string, revision: string, data: SkillInput, admin = false): Promise<AjaxResult<SkillDetail>> =>
  request({ url: `${base(admin)}/${id}`, method: 'put', data, headers: headers(revision) })
export const changeSkillStatus = (id: string, revision: string, status: SkillSummary['status'], reason: string, admin = false): Promise<AjaxResult<SkillDetail>> =>
  request({ url: `${base(admin)}/${id}/status`, method: 'post', data: { status, reason }, headers: headers(revision) })
export const checkSkillConnection = (id: string, admin = false): Promise<AjaxResult<{ success: boolean; checked?: string; errorCode?: string }>> =>
  request({ url: `${base(admin)}/${id}/connection-check`, method: 'post' })
export const deleteSkill = (id: string, revision: string, admin = false): Promise<AjaxResult> =>
  request({ url: `${base(admin)}/${id}`, method: 'delete', headers: headers(revision) })
