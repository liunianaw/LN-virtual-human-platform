import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import vm from 'node:vm'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'

const require = createRequire(import.meta.url)
const source = readFileSync(new URL('../src/views/login.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'login-security' }).content
const compiled = ts.transpileModule(script.replaceAll('import.meta.env', '({})'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true }
}).outputText

function setup(initialCookies = {}) {
  const cookies = new Map(Object.entries(initialCookies))
  const cookieApi = { get: key => cookies.get(key), set: (key, value) => cookies.set(key, String(value)), remove: key => cookies.delete(key) }
  const route = { query: {} }
  const module = { exports: {} }
  const defaults = value => ({ __esModule: true, default: value })
  const imports = {
    vue: require('vue'), 'js-cookie': defaults(cookieApi),
    '@/api/login': { getCodeImg: async () => ({ captchaEnabled: false }) },
    '@/store/modules/user': defaults(() => ({ login: async () => {} })),
    '@/settings': defaults({ footerContent: '' })
  }
  vm.runInNewContext(compiled, {
    module, exports: module.exports, require: name => { assert.ok(name in imports, `Unexpected login dependency: ${name}`); return imports[name] },
    ref: require('vue').ref, useRoute: () => route, useRouter: () => ({ push() {} }),
    getCurrentInstance: () => ({ proxy: { $refs: { loginRef: { validate: callback => callback(true) } } } }),
    watch: (_source, callback) => callback(route)
  })
  return { state: module.exports.default.setup({}, { expose() {} }), cookies }
}

test('a fresh login form has no supplied account or password', () => {
  const { state } = setup()
  assert.equal(state.loginForm.value.username, '')
  assert.equal(state.loginForm.value.password, '')
})

test('legacy remembered passwords are removed and never restored', () => {
  const { state, cookies } = setup({ username: 'remembered-user', password: 'legacy-secret', rememberMe: 'true' })
  assert.equal(state.loginForm.value.username, 'remembered-user')
  assert.equal(state.loginForm.value.password, '')
  assert.equal(cookies.has('password'), false)
  assert.equal(setup({ username: 'ignored-user', rememberMe: 'false' }).state.loginForm.value.username, '')
})

test('remembering an account never writes a password cookie', async () => {
  const { state, cookies } = setup()
  Object.assign(state.loginForm.value, { username: 'test-user', password: 'typed-secret', rememberMe: true })
  state.handleLogin()
  await Promise.resolve()
  assert.equal(cookies.get('username'), 'test-user')
  assert.equal(cookies.get('rememberMe'), 'true')
  assert.equal(cookies.has('password'), false)
})
