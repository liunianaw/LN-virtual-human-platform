<template>
  <main class="platform-home">
    <header class="welcome"><span class="eyebrow">LN VIRTUAL HUMAN PLATFORM</span><h1>欢迎回来，{{ user.nickName || user.name }}</h1><p>{{ user.isAdmin ? '管理官方服务、虚拟角色与声音资产。' : '制作和管理自己的角色与应用配置。' }}</p></header>
    <section aria-labelledby="shortcuts-title"><div class="section-heading"><h2 id="shortcuts-title">我的工作台</h2><span>菜单会随账号权限自动变化</span></div><div class="shortcuts"><router-link v-for="item in shortcuts" :key="item.path" class="shortcut" :to="item.path"><span>{{ item.title }}</span><p>{{ item.description }}</p><b>进入 →</b></router-link><router-link class="shortcut profile" to="/user/profile"><span>个人中心</span><p>维护个人资料与登录安全设置。</p><b>进入 →</b></router-link></div></section>
  </main>
</template>

<script setup lang="ts" name="Index">
import useUserStore from '@/store/modules/user'
import usePermissionStore from '@/store/modules/permission'
const user = useUserStore()
const routes = usePermissionStore()
const descriptions: Record<string, string> = { '角色制作': '上传参考图，制作并发布角色。', '我的角色': '查看、预览和管理个人角色版本。', '我的应用': '创建应用、发布固定配置并调试。', '虚拟角色与声音资产': '管理公共虚拟角色和官方声音的生命周期。', '角色管理': '配置平台用户角色及菜单权限，不管理虚拟角色资产。', '官方声音': '维护官方声音候选、试听与发布。', '官方服务': '配置平台可用的官方服务。' }
const shortcuts = computed(() => routes.sidebarRouters.flatMap((route: any) => route.children?.map((child: any) => ({ path: `${route.path}/${child.path}`.replace(/\/+/g, '/'), title: child.meta?.title, description: descriptions[child.meta?.title] || '进入此功能继续处理平台工作。' })) || [{ path: route.path, title: route.meta?.title, description: descriptions[route.meta?.title] || '进入此功能继续处理平台工作。' }]).filter((item: any) => item.title && item.path !== '/index').slice(0, 6))
</script>

<style scoped>
.platform-home { max-width: 1260px; margin: 0 auto; padding: 36px; }.welcome { padding: 42px; color: #edf8f8; background: radial-gradient(circle at 85% 20%, #20c9ab 0, transparent 22%), linear-gradient(135deg, #102a43, #155e75); border-radius: 18px; box-shadow: 0 20px 42px rgba(15, 53, 74, .16); }.eyebrow { color: #9ae6d7; font-size: 12px; font-weight: 700; letter-spacing: .16em; }.welcome h1 { margin: 16px 0 8px; font-family: "Microsoft YaHei", sans-serif; font-size: clamp(28px, 4vw, 42px); letter-spacing: -.05em; }.welcome p { margin: 0; color: #d4e7eb; }.section-heading { display: flex; align-items: baseline; justify-content: space-between; margin: 34px 0 16px; }.section-heading h2 { margin: 0; font-size: 20px; }.section-heading span { color: var(--el-text-color-secondary); font-size: 13px; }.shortcuts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }.shortcut { display: flex; min-height: 150px; flex-direction: column; padding: 22px; border: 1px solid #d8e6e7; border-radius: 14px; color: var(--el-text-color-primary); background: var(--el-bg-color); box-shadow: 0 8px 20px rgba(15, 53, 74, .05); transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease; }.shortcut:hover, .shortcut:focus-visible { border-color: #12a594; box-shadow: 0 14px 28px rgba(15, 89, 105, .13); transform: translateY(-3px); outline: none; }.shortcut span { font-size: 17px; font-weight: 700; }.shortcut p { margin: 10px 0 auto; color: var(--el-text-color-secondary); font-size: 14px; line-height: 1.65; }.shortcut b { margin-top: 14px; color: #0c8176; font-size: 13px; }.profile { background: #f0faf8; }@media (max-width: 820px) { .platform-home { padding: 20px; }.welcome { padding: 28px; }.shortcuts { grid-template-columns: 1fr; } }
</style>
