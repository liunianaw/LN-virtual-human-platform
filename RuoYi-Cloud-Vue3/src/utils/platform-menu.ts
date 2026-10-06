import type { RouterVo } from '../types/api/menu'
type Menu = RouterVo

/** Filter legacy menu records without changing the server response. */
export function filterPlatformMenus(menus: Menu[] = [], devtools = false, admin?: boolean, base = ''): Menu[] {
  return menus.flatMap(menu => {
    const component = (menu.component ?? '').replace(/^\//, '')
    // Menu ownership, labels, order and hierarchy come from RuoYi's authorized tree.
    if (/^(system\/(dept|post|notice)|tool\/build)(\/|$)/.test(component)) return []
    if (!devtools && /^tool\/gen(\/|$)/.test(component)) return []
    if (/^https?:\/\/(www\.)?ruoyi\.vip\/?$/.test(menu.path ?? '')) return []
    const copy = { ...menu }
    const destination = menu.path?.startsWith('/') ? menu.path : `${base}/${menu.path || ''}`.replace(/\/+/g, '/')
    if (component === 'avatar/index' && admin !== undefined) {
      copy.component = admin ? 'avatar/admin/index' : 'avatar/developer/index'
      copy.name = admin ? 'AdminAvatarProduction' : 'DeveloperAvatarProduction'
      copy.meta = { ...menu.meta, noCache: true }
    }
    // Aliases retain old links without adding, moving or renaming sidebar records.
    const legacy = component === 'avatar/admin/index' || component === 'avatar/developer/index' ? '/system/avatar'
      : component === 'voice/official/index' ? '/system/official-voices'
      : component === 'asset/public/index' ? '/system/public-assets'
      : component === 'official-service/index' ? '/system/official-services'
      : component === 'operations/index' ? '/operations/tasks'
      : component === 'application/index' ? '/system/applications'
      : component === 'developer/usage/index' ? '/system/usage'
      : component === 'developer/skill/index' ? (admin === false ? '/system/skills' : '/system/public-skills') : undefined
    if (legacy && legacy !== destination) {
      copy.alias = legacy
      copy.meta = { ...copy.meta, activeMenu: destination }
    }
    if (menu.children?.length) {
      copy.children = filterPlatformMenus(menu.children, devtools, admin, destination)
      if (!copy.children.length) return []
    }
    if (component === 'Layout' && !copy.children?.length) return []
    return [copy]
  })
}
