import type { RouterVo } from '../types/api/menu'
type Menu = RouterVo

/** Filter legacy menu records without changing the server response. */
export function filterPlatformMenus(menus: Menu[] = [], devtools = false): Menu[] {
  return menus.flatMap(menu => {
    const component = (menu.component ?? '').replace(/^\//, '')
    if (/^(system\/(dept|post|notice)|tool\/build)(\/|$)/.test(component)) return []
    if (!devtools && /^tool\/gen(\/|$)/.test(component)) return []
    if (/^https?:\/\/(www\.)?ruoyi\.vip\/?$/.test(menu.path ?? '')) return []
    const copy = { ...menu }
    if (menu.children?.length) {
      copy.children = filterPlatformMenus(menu.children, devtools)
      if (!copy.children.length) return []
    }
    return [copy]
  })
}
