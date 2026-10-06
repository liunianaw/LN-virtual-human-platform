import test from 'node:test'
import assert from 'node:assert/strict'
import { filterPlatformMenus } from '../src/utils/platform-menu.ts'

test('admin and developer menus use separate components and retain bookmark aliases', () => {
  const tree = [
    { name: 'System', path: '/system', component: 'Layout', children: [{ path: 'avatar', name: 'AdminAvatarProduction', component: 'avatar/admin/index' }, { path: 'public-skills', name: 'PublicSkills', component: 'developer/skill/index' }] },
    { name: 'DeveloperWorkspace', path: '/developer', component: 'Layout', children: [{ path: 'avatar', name: 'DeveloperAvatarProduction', component: 'avatar/developer/index' }, { path: 'applications', name: 'Applications', component: 'application/index' }] }
  ]
  const snapshot = structuredClone(tree)
  const admin = filterPlatformMenus([tree[0]], false, true)
  assert.equal(admin.length, 1)
  assert.equal(admin[0].children[0].component, 'avatar/admin/index')
  assert.equal(admin[0].children[1].name, 'PublicSkills')
  const developer = filterPlatformMenus([tree[1]], false, false)
  assert.equal(developer[0].children[0].component, 'avatar/developer/index')
  assert.equal(developer[0].children[0].alias, '/system/avatar')
  assert.equal(developer[0].children[1].alias, '/system/applications')
  assert.deepEqual(tree, snapshot)
})

test('old database menus cannot bring removed features back into the sidebar', () => {
  const tree = [{ component: 'Layout', children: [
    { component: 'system/dept/index' }, { component: 'system/post/index' },
    { component: 'system/notice/index' }, { component: 'tool/build/index' },
    { component: 'monitor/job/index', path: 'job' }, { component: 'system/user/index', path: 'user' }
  ] }]
  const result = filterPlatformMenus(tree, false)
  assert.deepEqual(result[0].children.map(r => r.path), ['job', 'user'])
  assert.equal(tree[0].children.length, 6)
})

test('generator is available only in explicit devtools mode and empty parents disappear', () => {
  const tree = [{ component: 'Layout', children: [{ component: 'tool/gen/index' }] }]
  assert.deepEqual(filterPlatformMenus(tree, false), [])
  assert.equal(filterPlatformMenus(tree, true)[0].children[0].component, 'tool/gen/index')
})

test('server categories, edited names, order and hierarchy remain authoritative', () => {
  const tree = [
    { path: '/renamed-calls', name: 'EditedCalls', component: 'Layout', meta: { title: '我调整的调用目录' }, children: [{ path: 'tasks', name: 'EditedTasks', component: 'operations/index', meta: { title: '调用记录' } }] },
    { path: '/resources', name: 'ResourceManagement', component: 'Layout', meta: { title: '资源管理' }, children: [{ path: 'voices', name: 'EditedVoices', component: 'voice/official/index', meta: { title: '声音配置' } }, { path: 'skills', name: 'EditedPublicSkills', component: 'developer/skill/index', meta: { title: '自定义公共技能' } }] }
  ]
  const original = structuredClone(tree)
  const result = filterPlatformMenus(tree, false, true)
  assert.deepEqual(result.map(item => item.meta.title), ['我调整的调用目录', '资源管理'])
  assert.deepEqual(result[1].children.map(item => item.meta.title), ['声音配置', '自定义公共技能'])
  assert.equal(result[0].children[0].alias, '/operations/tasks')
  assert.equal(result[0].children[0].meta.activeMenu, '/renamed-calls/tasks')
  assert.equal(result[1].children[0].alias, '/system/official-voices')
  assert.deepEqual(tree, original)
  assert.deepEqual(filterPlatformMenus([{ component: 'Layout', path: '/empty' }]), [])
})
