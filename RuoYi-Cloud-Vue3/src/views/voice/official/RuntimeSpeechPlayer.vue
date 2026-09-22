<template>
  <div class="runtime-player">
    <div ref="canvasHost" class="avatar" />
    <el-input v-model="text" type="textarea" :rows="3" maxlength="8000" show-word-limit placeholder="输入试听文本；点击播报才会触发合成。" />
    <div class="actions"><el-button type="primary" :disabled="!ready || !text.trim()" @click="speak">播报</el-button><el-button :disabled="!turnId" @click="stop">停止</el-button><el-button v-if="blocked" @click="resume">点击继续播放</el-button><span>{{ status }}</span></div>
  </div>
</template>
<script setup lang="ts">
import { createRuntimeConnectionTicket, getRuntimeAvatarPackage } from '@/api/asset/official-voice'
import { createAvatar, type AvatarPlayer } from '@ln-avatar/sdk'
const props = defineProps<{ token: string }>()
const canvasHost = ref<HTMLElement>(); const text = ref(''); const status = ref('正在加载角色…'); const ready = ref(false); const turnId = ref(''); const blocked = ref(false)
let socket: WebSocket | undefined; let epoch = ''; let audio: HTMLAudioElement | undefined; let player: AvatarPlayer | undefined; const queue: any[] = []; let playing = false; let active: any
function send(type: string, data: Record<string, unknown>, extra: Record<string, unknown> = {}) { socket?.send(JSON.stringify({ v: 1, type, connectionEpoch: epoch, ...extra, data })) }
function endpoint() { return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/v1/realtime` }
async function connect() {
  try {
    const packageResponse = await getRuntimeAvatarPackage(props.token); if (!packageResponse.data || !canvasHost.value) throw new Error('未获得正式角色包')
    player = createAvatar({ container: canvasHost.value }); player.on('audio.blocked', () => { blocked.value = true; status.value = '浏览器阻止自动播放，请点击继续播放' }); await player.loadPackage(packageResponse.data)
    const response = await createRuntimeConnectionTicket(props.token)
    const ticket = response.data?.ticket; if (!ticket) throw new Error('未获得连接票据')
    socket = new WebSocket(endpoint(), 'ln-avatar.v1')
    socket.onopen = () => socket?.send(JSON.stringify({ v: 1, type: 'connection.auth', data: { ticket } }))
    socket.onmessage = event => receive(JSON.parse(event.data))
    socket.onclose = () => { ready.value = false; player?.stop(); status.value = '连接已关闭' }
  } catch { status.value = '无法加载正式角色或建立调试连接' }
}
function receive(message: any) {
  if (message.type === 'connection.ready') { epoch = message.connectionEpoch; ready.value = true; status.value = '已连接，等待输入'; return }
  if (message.type === 'request.ack') { turnId.value = message.turnId; status.value = '正在合成'; return }
  if (message.type === 'audio.segment') { if (message.connectionEpoch === epoch && message.turnId === turnId.value) { queue.push(message); playNext() } }
  if (message.type === 'turn.stopped' || message.type === 'turn.failed') { turnId.value = ''; status.value = message.type === 'turn.stopped' ? '已停止' : '合成失败' }
  if (message.type === 'request.error') status.value = `请求失败：${message.data?.code || 'UNKNOWN'}`
}
async function playNext() {
  if (playing || !queue.length) return
  playing = true; const message = queue.shift(); active = message
  const response = await fetch(`/api/v1/runtime/media/${message.data.mediaId}`, { headers: { Authorization: `Bearer ${props.token}` }, cache: 'no-store' })
  if (!response.ok) { status.value = '音频已不可用'; playing = false; send('playback.report', { segmentId: message.data.segmentId, state: 'FAILED' }, { turnId: message.turnId }); playNext(); return }
  const url = URL.createObjectURL(await response.blob()); audio = new Audio(url)
  audio.onplay = () => { blocked.value = false; status.value = '正在播放'; send('playback.report', { segmentId: message.data.segmentId, state: 'STARTED' }, { turnId: message.turnId }) }
  audio.onended = () => finish(url, 'ENDED', '播放完成')
  audio.onerror = () => finish(url, 'FAILED', '播放失败')
  player?.playAudio(audio).catch(() => {})
}
function finish(url: string, state: 'ENDED' | 'FAILED', message: string) { URL.revokeObjectURL(url); if (active) send('playback.report', { segmentId: active.data.segmentId, state }, { turnId: active.turnId }); active = undefined; playing = false; if (!queue.length) { turnId.value = ''; status.value = message }; playNext() }
function resume() { if (audio) player?.playAudio(audio).catch(() => {}); blocked.value = false }
function speak() { if (!text.value.trim()) return; send('speech.create', { text: text.value.trim() }, { requestId: crypto.randomUUID() }) }
function stop() { queue.splice(0); blocked.value = false; player?.stop(); audio?.pause(); playing = false; if (turnId.value) send('turn.stop', { reason: 'USER_STOP' }, { turnId: turnId.value }); turnId.value = ''; status.value = '已停止' }
onMounted(connect)
onUnmounted(() => { stop(); socket?.close(); player?.destroy() })
</script>
<style scoped>.avatar { min-height: 180px; display:flex; justify-content:center; }.avatar :deep(canvas) { max-height:260px; max-width:100%; }.actions { display:flex; align-items:center; gap:8px; margin-top:8px; }.actions span { color:var(--el-text-color-secondary); font-size:13px; }</style>
