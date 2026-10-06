<template>
  <section v-loading="loading" class="avatar-resource-preview">
    <img v-if="imageUrl && !imageFailed" :src="imageUrl" :alt="name" @error="imageFailed = true" />
    <p v-else class="resource-fallback">{{ failed || imageFailed ? '形象预览暂不可用，请重试' : '该版本暂无可用形象预览' }}</p>
    <el-button v-if="failed || imageFailed" link type="primary" @click="load">重新加载预览</el-button>
    <div v-if="preview?.actions?.length" class="action-selector"><el-select v-model="actionCode" placeholder="选择动作" aria-label="选择预览动作"><el-option v-for="action in preview.actions" :key="action.actionCode" :value="action.actionCode" :label="action.actionCode" /></el-select><el-button :disabled="!activeAction?.atlasUrl || !frames.length || reducedMotion" @click="playing = !playing">{{ playing ? '停止动作' : '预览动作' }}</el-button><span v-if="reducedMotion">已按系统设置减少动效</span></div>
    <ActionPreview v-if="playing && activeAction?.atlasUrl && frames.length" :url="activeAction.atlasUrl" :frames="frames" :fps="activeAction.fps" :loop="activeAction.loopEnabled" />
  </section>
</template>
<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { getAvatarVersionPreview, type AvatarVersionPreview, type AvatarActionPreview } from '@/api/asset/avatar'
import ActionPreview from '@/views/avatar/ActionPreview.vue'
const props = defineProps<{ avatarId: string; versionId: string; name: string }>()
const preview = ref<AvatarVersionPreview>(), loading = ref(false), failed = ref(false), imageFailed = ref(false), playing = ref(false), actionCode = ref('')
const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
const imageUrl = computed(() => preview.value?.baseImageUrl || preview.value?.previewUrl || '')
const activeAction = computed(() => preview.value?.actions?.find((action: AvatarActionPreview) => action.actionCode === actionCode.value))
const frames = computed(() => { try { const layout = JSON.parse(activeAction.value?.frameLayout || '{}'); return Array.isArray(layout.frames) ? layout.frames : [] } catch { return [] } })
let requestId = 0
async function load() { const request = ++requestId; loading.value = true; failed.value = false; imageFailed.value = false; playing.value = false; preview.value = undefined; try { const response = await getAvatarVersionPreview(props.avatarId, props.versionId); if (request === requestId) { preview.value = response.data; actionCode.value = response.data?.actions?.[0]?.actionCode || '' } } catch { if (request === requestId) failed.value = true } finally { if (request === requestId) loading.value = false } }
watch([() => props.avatarId, () => props.versionId], load, { immediate: true })
watch([actionCode, reducedMotion], () => { playing.value = false })
onBeforeUnmount(() => { requestId++ })
</script>
<style scoped>
.avatar-resource-preview > img { display: block; width: 100%; height: 320px; object-fit: contain; margin: 0 auto 18px; }.action-selector { display: flex; gap: 12px; flex-wrap: wrap; margin: 18px 0; align-items: center; }.action-selector span { color: var(--ln-muted); font-size: 12px; }.action-selector .el-select { width: 170px; }
</style>
