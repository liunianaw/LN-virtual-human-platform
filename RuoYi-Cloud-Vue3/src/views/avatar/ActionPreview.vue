<template><canvas ref="canvas" class="action-canvas" :width="width" :height="height" /></template>

<script setup lang="ts">
const props = defineProps<{ url: string; frames: Array<{ x: number; y: number; width: number; height: number }>; fps: number; loop: boolean }>()
const canvas = ref<HTMLCanvasElement>()
const width = computed(() => props.frames[0]?.width || 512)
const height = computed(() => props.frames[0]?.height || 768)
let timer: number | undefined
let image: HTMLImageElement | undefined

function stop() { if (timer !== undefined) window.clearInterval(timer); timer = undefined }
function play() {
  stop()
  if (!canvas.value || !props.url || !props.frames.length) return
  image = new Image()
  image.crossOrigin = 'anonymous'
  image.onload = () => {
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
  image.src = props.url
}

watch(() => [props.url, props.frames, props.fps, props.loop], play, { deep: true })
onMounted(play)
onUnmounted(stop)
</script>

<style scoped>.action-canvas { display: block; width: 100%; max-height: 280px; object-fit: contain; background: #f3f4f6; }</style>
