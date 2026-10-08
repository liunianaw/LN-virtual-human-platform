<template>
  <el-breadcrumb class="app-breadcrumb" separator="" aria-label="页面路径">
    <el-breadcrumb-item v-for="(item, index) in levelList" :key="item.path">
      <router-link :to="destination(item)" class="breadcrumb-link" :aria-current="index === levelList.length - 1 ? 'page' : undefined">
        {{ item.meta.title }}
      </router-link>
      <el-dropdown v-if="item.children.length" trigger="click" placement="bottom-start" @command="navigate">
        <button class="breadcrumb-arrow" :aria-label="`${item.meta.title}的下级菜单`" type="button">
          <el-icon><ArrowRight /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-for="child in item.children" :key="child.path" :command="destination(child)">
              {{ child.meta.title }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span v-else-if="index < levelList.length - 1" class="breadcrumb-separator" aria-hidden="true">›</span>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import usePermissionStore from '@/store/modules/permission'

interface PathNode {
  path: string
  meta: { title: string; breadcrumb?: boolean }
  query?: string
  children: PathNode[]
}

const route = useRoute()
const router = useRouter()
const permissionStore = usePermissionStore()
const levelList = ref<PathNode[]>([])

// Router records may flatten ParentView levels; retain the authorized menu tree.
function menuNodes(routes: any[], base = ''): PathNode[] {
  return routes.flatMap(item => {
    if (item.hidden) return []
    const path = item.path ? (item.path.startsWith('/') ? item.path : `${base}/${item.path}`.replace(/\/+/g, '/')) : (base || '/')
    const children = menuNodes(item.children || [], path)
    return item.meta?.title && item.meta.breadcrumb !== false ? [{ ...item, path, children }] : children
  })
}

function findTrail(nodes: PathNode[], path: string): PathNode[] | undefined {
  for (const node of nodes) {
    const children = findTrail(node.children, path)
    if (children) return [node, ...children]
    if (node.path === path) return [node]
  }
}

function destination(item: PathNode): RouteLocationRaw {
  if (item.path === '/index') return { path: '/index' }
  const page = item.children.length ? destination(item.children[0]) : { path: item.path }
  return item.query ? { ...(typeof page === 'string' ? { path: page } : page), query: JSON.parse(item.query) } : page
}

function navigate(target: RouteLocationRaw): void {
  router.push(target)
}

watchEffect(() => {
  if (route.path.startsWith('/redirect/')) return
  const nodes = menuNodes(permissionStore.defaultRoutes)
  const home: PathNode = { path: '/index', meta: { title: '首页' }, children: [] }
  const topLevel = nodes.filter(item => item.path !== '/index')
  const trail = findTrail(nodes, String(route.meta.activeMenu || route.path))
    || route.matched.filter(item => item.meta?.title && item.meta.breadcrumb !== false)
      .map(item => ({ path: item.path.includes(':') ? route.path : item.path, meta: item.meta as PathNode['meta'], children: [] }))
  const currentTitle = route.meta.title as string | undefined
  if (route.meta.activeMenu && currentTitle && currentTitle !== trail[trail.length - 1]?.meta.title) {
    trail.push({ path: route.path, meta: { title: currentTitle }, children: [] })
  }
  levelList.value = [{ ...home, children: topLevel }, ...trail.filter(item => item.path !== '/index')]
})
</script>

<style lang="scss" scoped>
.app-breadcrumb.el-breadcrumb {
  display: flex; align-items: center; overflow-x: auto; scrollbar-width: none;
  font-size: 14px; line-height: var(--ln-header, 50px);
  :deep(.el-breadcrumb__item) { flex: none; }
  :deep(.el-breadcrumb__inner) { display: inline-flex; align-items: center; font-weight: 400; }
  :deep(.el-breadcrumb__separator) { display: none; }
  .breadcrumb-link { padding: 6px 8px; border-radius: 6px; line-height: 20px; white-space: nowrap; color: var(--ln-muted); font-weight: 400; }
  .breadcrumb-link[aria-current="page"] { color: var(--ln-text); }
  .breadcrumb-link:hover, .breadcrumb-arrow:hover { color: var(--ln-accent); background: var(--ln-selected); }
  .breadcrumb-arrow { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 32px; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--ln-muted); cursor: pointer; }
  .breadcrumb-separator { padding: 0 6px; color: var(--ln-muted); }
}
</style>
