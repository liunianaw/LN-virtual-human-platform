<template>
  <div>
    <canvas v-show="!loadError" ref="canvas" class="action-canvas" :width="width" :height="height" />
    <el-alert v-if="loadError" title="图集加载失败，请重新点击预览" type="error" :closable="false" />
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{ url: string; frames: Array<{ x: number; y: number; width: number; height: number }>; fps: number; loop: boolean }>()
const canvas = ref<HTMLCanvasElement>()
const width = computed(() => props.frames[0]?.width || 512)
const height = computed(() => props.frames[0]?.height || 768)
const loadError = ref(false)
let timer: number | undefined
let image: HTMLImageElement | undefined
let loadVersion = 0

function stop() {
  if (timer !== undefined) window.clearInterval(timer)
  timer = undefined
  if (image) image.onload = image.onerror = null
  image = undefined
  loadVersion += 1
}
function play() {
  stop()
  loadError.value = false
  if (!canvas.value || !props.url || !props.frames.length) return
  image = new Image()
  // We only draw the private COS image; reading pixels from the canvas is not required.
  // Requiring CORS here prevents onload when the bucket does not expose CORS headers.
  const version = loadVersion
  image.onload = () => {
    if (version !== loadVersion) return
    let index = 0
    const draw = () => {
      const frame = props.frames[index]
      const context = canvas.value?.getContext('2d')
      if (!context || !image) return
      context.clearRect(0, 0, width.value, height.value)
      context.drawImage(image, frame.x, frame.y, frame.width, frame.height, 0, 0, width.value, height.value)
      index += 1
      if (index >= props.frames.length) {
        if (props.loop) index = 0
        else stop()
      }
    }
    draw()
    timer = window.setInterval(draw, 1000 / Math.max(1, props.fps || 6))
  }
  image.onerror = () => { if (version === loadVersion) loadError.value = true }
  image.src = props.url
}

watch(() => [props.url, props.frames, props.fps, props.loop], play, { deep: true })
onMounted(play)
onUnmounted(stop)
</script>

<style scoped>.action-canvas { display: block; width: 100%; max-height: 280px; object-fit: contain; background: #f3f4f6; }</style>
