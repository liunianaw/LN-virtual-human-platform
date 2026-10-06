<template>
  <main class="platform-home">
    <header class="welcome"><h1>欢迎回来，{{ user.nickName || user.name }}</h1><p>{{ user.isAdmin ? '管理官方服务、虚拟角色与声音资产。' : '制作和管理自己的角色与应用配置。' }}</p></header>
    <section aria-labelledby="shortcuts-title"><div class="section-heading"><h2 id="shortcuts-title">我的工作台</h2><span>菜单会随账号权限自动变化</span></div><div class="shortcuts"><router-link v-for="item in shortcuts" :key="item.path" class="shortcut" :to="item.path"><span>{{ item.title }}</span><p>{{ item.description }}</p><b>进入 →</b></router-link><router-link class="shortcut profile" to="/user/profile"><span>个人中心</span><p>维护个人资料与登录安全设置。</p><b>进入 →</b></router-link></div></section>
  </main>
</template>

<script setup lang="ts" name="Index">
import useUserStore from '@/store/modules/user'
import usePermissionStore from '@/store/modules/permission'
const user = useUserStore()
const routes = usePermissionStore()
const descriptions: Record<string, string> = { '角色制作': '上传参考图，制作并发布角色。', '我的角色': '查看、预览和管理个人角色版本。', '我的应用': '选择角色、声音与行为，保存应用当前配置。', '虚拟角色与声音资产': '管理公共虚拟角色和官方声音的生命周期。', '角色管理': '配置平台用户角色及菜单权限，不管理虚拟角色资产。', '官方声音': '维护官方声音候选、试听与发布。', '官方服务': '配置平台可用的官方服务。' }
const shortcuts = computed(() => routes.sidebarRouters.flatMap((route: any) => route.children?.map((child: any) => ({ path: `${route.path}/${child.path}`.replace(/\/+/g, '/'), title: child.meta?.title, description: descriptions[child.meta?.title] || '进入此功能继续处理平台工作。' })) || [{ path: route.path, title: route.meta?.title, description: descriptions[route.meta?.title] || '进入此功能继续处理平台工作。' }]).filter((item: any) => item.title && item.path !== '/index').slice(0, 6))
</script>

<style scoped>
.platform-home { padding: 34px; }.welcome { padding: 12px 0 34px; border-bottom: 1px solid var(--ln-line); }.welcome h1 { margin: 0 0 12px; font-size: 26px; font-weight: 600; letter-spacing: -.025em; }.welcome p { margin: 0; color: var(--ln-muted); font-size: 14px; line-height: 1.7; }.section-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin: 30px 0 8px; }.section-heading h2 { font-size: 14px; font-weight: 600; }.section-heading span { color: var(--ln-muted); font-size: 14px; }.shortcuts { display: grid; grid-template-columns: 1fr 1fr; gap: 0 36px; }.shortcut { padding: 24px 0; border-bottom: 1px solid var(--ln-line); }.shortcut span { font-size: 16px; font-weight: 600; }.shortcut p { color: var(--ln-muted); line-height: 1.7; font-size: 15px; margin: 8px 0 12px; }.shortcut b { font-size: 14px; font-weight: 500; color: var(--ln-accent); }.shortcut:hover span { color: var(--ln-accent); }@media(max-width:767px) { .shortcuts { grid-template-columns: 1fr; }.section-heading { display: block; }.welcome h1 { font-size: 22px; } }
</style>
