import test from 'node:test'
import assert from 'node:assert/strict'
import { filterPlatformMenus } from '../src/utils/platform-menu.ts'

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
