<template>
  <main class="platform-home">
    <header class="welcome">
      <span class="eyebrow">LN · 虚拟人开放平台</span>
      <h1>欢迎回来，{{ user.nickName || user.name }}</h1>
      <p>管理账号、配置权限，从这里开始。</p>
    </header>
    <section aria-labelledby="shortcuts-title">
      <h2 id="shortcuts-title">常用管理</h2>
      <div class="shortcuts">
        <router-link class="shortcut" to="/user/profile"><span>个人中心</span><p>维护个人资料与登录密码</p></router-link>
        <router-link v-hasPermi="['system:user:list']" class="shortcut" to="/system/user"><span>用户管理</span><p>管理平台账号与角色分配</p></router-link>
        <router-link v-hasPermi="['system:role:list']" class="shortcut" to="/system/role"><span>角色权限</span><p>配置管理菜单与操作权限</p></router-link>
        <router-link v-hasPermi="['monitor:job:list']" class="shortcut" to="/monitor/job"><span>定时任务</span><p>管理任务调度与执行日志</p></router-link>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts" name="Index">
import useUserStore from '@/store/modules/user'
const user = useUserStore()
</script>

<style scoped>
.platform-home { padding: 40px; max-width: 1200px; margin: 0 auto; }
.welcome { padding: 28px 0 36px; border-bottom: 1px solid var(--el-border-color-light); margin-bottom: 32px; }
.eyebrow { font-size: 13px; color: var(--el-color-primary); letter-spacing: 2px; }
h1 { margin: 18px 0 12px; font-size: clamp(24px, 3vw, 34px); font-weight: 600; color: var(--el-text-color-primary); }
h2 { font-size: 17px; font-weight: 600; margin: 0 0 20px; }
p { line-height: 1.7; color: var(--el-text-color-secondary); }
.shortcuts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.shortcut { display: block; padding: 24px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-bg-color); transition: border-color .15s; }
.shortcut:hover, .shortcut:focus-visible { border-color: var(--el-color-primary); outline: 2px solid var(--el-color-primary-light-8); }
.shortcut span { font-size: 17px; font-weight: 600; }
.shortcut p { margin-bottom: 0; font-size: 14px; }
@media (max-width: 640px) { .platform-home { padding: 20px; } .shortcuts { grid-template-columns: 1fr; } }
</style>
