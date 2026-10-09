import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'

const require = createRequire(import.meta.url)
const vue = require('vue')
const httpCrypto = { getRandomValues: value => webcrypto.getRandomValues(value) }
const uuidPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/
const compile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText
const idSource = readFileSync(new URL('../../avatar-sdk/src/request-id.ts', import.meta.url), 'utf8')
function loadId(crypto) {
  const exports = {}
  vm.runInNewContext(compile(idSource), { exports, crypto })
  return exports.createRequestId
}

test('HTTP fallback produces distinct UUID v4 IDs from secure random bytes', () => {
  const createRequestId = loadId(httpCrypto)
  const ids = Array.from({ length: 100 }, createRequestId)
  assert.equal(new Set(ids).size, ids.length)
  for (const id of ids) assert.match(id, uuidPattern)
  const zeros = loadId({ getRandomValues: bytes => bytes.fill(0) })
  assert.equal(zeros(), '00000000-0000-4000-8000-000000000000')
})

test('secure contexts retain the native UUID implementation', () => {
  const expected = webcrypto.randomUUID()
  assert.equal(loadId({ randomUUID: () => expected })(), expected)
})

test('official voice setup and a new audition work without crypto.randomUUID', () => {
  const source = readFileSync(new URL('../src/views/voice/official/index.vue', import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: 'http-voice' }).content
  const module = { exports: {} }
  vm.runInNewContext(compile(script), {
    module, exports: module.exports, crypto: httpCrypto,
    require: name => {
      if (name === 'vue') return vue
      if (name === '@ln-avatar/sdk') return { createRequestId: loadId(httpCrypto) }
      assert.equal(name, '@/api/asset/official-voice')
      return {}
    },
    ref: vue.ref, reactive: vue.reactive, computed: vue.computed,
    getCurrentInstance: () => ({ proxy: { $modal: { msgSuccess() {} } } }),
    onMounted() {}, onBeforeUnmount() {}
  })
  const state = module.exports.default.setup({}, { expose() {} })
  const first = state.auditionKey.value
  assert.match(first, uuidPattern)
  state.newAudition()
  assert.match(state.auditionKey.value, uuidPattern)
  assert.notEqual(state.auditionKey.value, first)
  assert.equal(state.auditionRequest.value, '')
})
