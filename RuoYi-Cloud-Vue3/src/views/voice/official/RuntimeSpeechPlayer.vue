<template>
  <div class="runtime-player">
    <el-input v-model="text" type="textarea" :rows="3" maxlength="8000" show-word-limit placeholder="输入试听文本；点击播报才会触发合成。" />
    <div class="actions"><el-button type="primary" :disabled="!ready || !text.trim()" @click="speak">播报</el-button><el-button :disabled="!turnId" @click="stop">停止</el-button><span>{{ status }}</span></div>
  </div>
</template>
<script setup lang="ts">
import { createRuntimeConnectionTicket } from '@/api/asset/official-voice'
const props = defineProps<{ token: string }>()
const text = ref(''); const status = ref('正在连接…'); const ready = ref(false); const turnId = ref(''); let socket: WebSocket | undefined; let epoch = ''; let audio: HTMLAudioElement | undefined; const queue: any[] = []; let playing = false
function send(type: string, data: Record<string, unknown>, extra: Record<string, unknown> = {}) { socket?.send(JSON.stringify({ v: 1, type, connectionEpoch: epoch, ...extra, data })) }
function endpoint() { return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/v1/realtime` }
function connect() {
  createRuntimeConnectionTicket(props.token).then(response => {
    const ticket = response.data?.ticket; if (!ticket) throw new Error('未获得连接票据')
    socket = new WebSocket(endpoint(), 'ln-avatar.v1')
    socket.onopen = () => socket?.send(JSON.stringify({ v: 1, type: 'connection.auth', data: { ticket } }))
    socket.onmessage = event => receive(JSON.parse(event.data))
    socket.onclose = () => { ready.value = false; status.value = '连接已关闭' }
  }).catch(() => { status.value = '无法建立试听连接' })
}
function receive(message: any) {
  if (message.type === 'connection.ready') { epoch = message.connectionEpoch; ready.value = true; status.value = '已连接，等待输入'; return }
  if (message.type === 'request.ack') { turnId.value = message.turnId; status.value = '正在合成'; return }
  if (message.type === 'audio.segment') { queue.push(message); playNext() }
  if (message.type === 'turn.stopped' || message.type === 'turn.failed') { turnId.value = ''; status.value = message.type === 'turn.stopped' ? '已停止' : '合成失败' }
  if (message.type === 'request.error') status.value = `请求失败：${message.data?.code || 'UNKNOWN'}`
}
async function playNext() {
  if (playing || !queue.length) return
  playing = true; const message = queue.shift()
  const response = await fetch(`/api/v1/runtime/media/${message.data.mediaId}`, { headers: { Authorization: `Bearer ${props.token}` }, cache: 'no-store' })
  if (!response.ok) { status.value = '音频已不可用'; playing = false; playNext(); return }
  const url = URL.createObjectURL(await response.blob()); audio = new Audio(url)
  audio.onplay = () => { status.value = '正在播放'; send('playback.report', { segmentId: message.data.segmentId, state: 'STARTED' }, { turnId: message.turnId }) }
  audio.onended = () => { URL.revokeObjectURL(url); send('playback.report', { segmentId: message.data.segmentId, state: 'ENDED' }, { turnId: message.turnId }); playing = false; if (!queue.length) { turnId.value = ''; status.value = '播放完成' }; playNext() }
  audio.onerror = () => { URL.revokeObjectURL(url); send('playback.report', { segmentId: message.data.segmentId, state: 'FAILED' }, { turnId: message.turnId }); playing = false; status.value = '播放失败'; playNext() }
  try { await audio.play() } catch { audio.onerror?.(new Event('error')) }
}
function speak() { if (!text.value.trim()) return; send('speech.create', { text: text.value.trim() }, { requestId: crypto.randomUUID() }) }
function stop() { queue.splice(0); playing = false; audio?.pause(); if (turnId.value) send('turn.stop', { reason: 'USER_STOP' }, { turnId: turnId.value }); turnId.value = ''; status.value = '已停止' }
onMounted(connect)
onUnmounted(() => { stop(); socket?.close(); audio?.pause() })
</script>
<style scoped>.actions { display:flex; align-items:center; gap:8px; margin-top:8px; }.actions span { color:var(--el-text-color-secondary); font-size:13px; }</style>
