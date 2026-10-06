<template><div class="app-container"><p>正在打开角色制作…</p></div></template>
<script setup lang="ts">
import useUserStore from '@/store/modules/user'
const route = useRoute(), router = useRouter(), user = useUserStore()
onMounted(() => router.replace({ path: user.isAdmin ? '/system/avatar' : '/developer/avatar', query: route.query }))
</script>
