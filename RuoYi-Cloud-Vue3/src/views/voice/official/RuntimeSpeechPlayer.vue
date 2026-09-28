<template>
  <div class="runtime-player">
    <div ref="canvasHost" class="avatar" />
    <el-input v-model="text" type="textarea" :rows="3" :maxlength="props.mode === 'CHAT' ? 4096 : 8000" show-word-limit :placeholder="props.mode === 'CHAT' ? '输入对话内容；点击发送才会调用开发者 Relay。' : '输入试听文本；点击播报才会触发合成。'" />
    <el-input v-if="props.mode === 'CHAT'" :model-value="responseText" type="textarea" :rows="5" readonly placeholder="当前轮流式文本" />
    <div v-if="props.mode === 'CHAT'" class="actions">
      <span>页面感知</span><el-select v-model="contextMode" style="width:130px"><el-option label="关闭" value="NONE" /><el-option label="显式采集" value="EXPLICIT" /><el-option label="AI 按需" value="AI_ON_DEMAND" /></el-select>
      <el-select v-if="contextMode !== 'NONE'" v-model="contextSource" style="width:110px"><el-option label="Page" value="PAGE" /><el-option label="Hybrid" value="HYBRID" /></el-select>
      <el-select v-if="contextMode === 'EXPLICIT'" v-model="contextCoverage" style="width:120px"><el-option label="当前视口" value="VIEWPORT" /><el-option label="全页" value="FULL_PAGE" /></el-select>
      <el-select v-if="contextMode === 'EXPLICIT'" v-model="contextResultMode" style="width:110px"><el-option label="部分可用" value="PARTIAL" /><el-option label="严格" value="STRICT" /></el-select>
      <el-button v-if="contextSource === 'HYBRID'" @click="pageContext?.clear()">清除高亮</el-button>
    </div>
    <div class="actions"><el-button type="primary" :disabled="!ready || !text.trim()" @click="submit">{{ props.mode === 'CHAT' ? '发送' : '播报' }}</el-button><el-button v-if="props.mode === 'CHAT' && !recording" :disabled="!ready || recognizing" @click="startRecording">录音</el-button><el-button v-if="recording" @click="finishRecording">结束识别</el-button><el-button v-if="recording || recognizing" @click="cancelRecording">取消录音</el-button><el-button :disabled="!turnId && !pendingRequest" @click="stop">停止</el-button><el-button v-if="blocked" @click="resume">点击继续播放</el-button><span>{{ status }}</span></div>
  </div>
</template>
<script setup lang="ts">
import { createRuntimeConnectionTicket, getRuntimeAvatarPackage } from '@/api/asset/official-voice'
import { createAvatar, type AvatarPlayer } from '@ln-avatar/sdk'
import type { PageContext, ContextRequest } from '@ln-avatar/sdk/context'
const props = withDefaults(defineProps<{ token: string; mode?: 'CHAT' | 'SPEAK_ONLY' }>(), { mode: 'SPEAK_ONLY' })
const canvasHost = ref<HTMLElement>(); const text = ref(''); const responseText = ref(''); const status = ref('正在加载角色…'); const ready = ref(false); const turnId = ref(''); const blocked = ref(false); const recording = ref(false); const recognizing = ref(false)
const contextMode = ref<'NONE' | 'EXPLICIT' | 'AI_ON_DEMAND'>('NONE'); const contextSource = ref<'PAGE' | 'HYBRID'>('PAGE'); const contextCoverage = ref<'VIEWPORT' | 'FULL_PAGE'>('VIEWPORT'); const contextResultMode = ref<'PARTIAL' | 'STRICT'>('PARTIAL')
let pageContext: PageContext | undefined; let contextAbort: AbortController | undefined
let socket: WebSocket | undefined; let epoch = ''; let audio: HTMLAudioElement | undefined; let audioUrl = ''; let mediaAbort: AbortController | undefined; let asrAbort: AbortController | undefined; let player: AvatarPlayer | undefined; const queue: any[] = []; let playing = false; let active: any; let generation = 0; let recordingGeneration = 0; let pendingRequest = ''; let stopOnAck = false; let recorder: MediaRecorder | undefined; let microphone: MediaStream | undefined; let chunks: Blob[] = []
function send(type: string, data: Record<string, unknown>, extra: Record<string, unknown> = {}) { const requestId = crypto.randomUUID(); socket?.send(JSON.stringify({ v: 1, type, requestId, connectionEpoch: epoch, ...extra, data })); return requestId }
function endpoint() { return `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/v1/realtime` }
async function connect() {
  try {
    const packageResponse = await getRuntimeAvatarPackage(props.token); if (!packageResponse.data || !canvasHost.value) throw new Error('未获得正式角色包')
    player = createAvatar({ container: canvasHost.value }); player.on('audio.blocked', () => { blocked.value = true; status.value = '浏览器阻止自动播放，请点击继续播放' }); await player.loadPackage(packageResponse.data)
    const response = await createRuntimeConnectionTicket(props.token)
    const ticket = response.data?.ticket; if (!ticket) throw new Error('未获得连接票据')
    socket = new WebSocket(endpoint(), 'ln-avatar.v1')
    socket.onopen = () => socket?.send(JSON.stringify({ v: 1, type: 'connection.auth', requestId: crypto.randomUUID(), data: { ticket } }))
    socket.onmessage = event => receive(JSON.parse(event.data))
    socket.onclose = () => { ready.value = false; clearAudio(); cancelRecording(); status.value = '连接已关闭' }
  } catch { status.value = '无法加载正式角色或建立调试连接' }
}
function receive(message: any) {
  if (message.type === 'connection.ready') { epoch = message.connectionEpoch; ready.value = true; status.value = '已连接，等待输入'; return }
  if (message.type === 'request.ack' && message.requestId === pendingRequest) { pendingRequest = ''; if (stopOnAck) { stopOnAck = false; send('turn.stop', { reason: 'USER_STOP' }, { turnId: message.turnId }); return } clearAudio(); turnId.value = message.turnId; if (props.mode === 'CHAT') responseText.value = ''; status.value = props.mode === 'CHAT' ? '正在生成' : '正在合成'; return }
  if (message.type === 'text.delta' && message.connectionEpoch === epoch && message.turnId === turnId.value) { responseText.value += String(message.data?.text || ''); return }
  if (message.type === 'text.completed' && message.turnId === turnId.value) { status.value = '文字完成，正在播报'; return }
  if (message.type === 'context.request' && message.turnId === turnId.value) { void captureContext(message); return }
  if (message.type === 'context.received' && message.turnId === turnId.value) { status.value = `页面采集：图片 ${message.data?.screenshotStatus}，文本 ${message.data?.domStatus}`; return }
  if (message.type === 'guidance.proposed' && message.turnId === turnId.value) {
    const result = pageContext?.highlight(String(message.data?.captureRequestId || ''), String(message.data?.elementRef || ''), message.data?.scrollIntoView === true) || 'TARGET_STALE'
    send('guidance.result', { guidanceId: message.data?.guidanceId, captureRequestId: message.data?.captureRequestId, status: result }, { turnId: message.turnId })
    status.value = result === 'EVENT_ONLY' ? '收到高亮建议，可由页面自行处理' : `高亮：${result}`; return
  }
  if (message.type === 'turn.completed' && message.turnId === turnId.value) { clearAudio(); turnId.value = ''; status.value = '播放完成'; return }
  if (message.type === 'audio.segment') { if (message.connectionEpoch === epoch && message.turnId === turnId.value) { queue.push(message); playNext() } }
  if (message.type === 'turn.stopped' || message.type === 'turn.failed') { clearAudio(); turnId.value = ''; status.value = message.type === 'turn.stopped' ? '已停止' : '合成失败，文字仍可查看' }
  if (message.type === 'audio.failed') status.value = '合成失败，文字仍可查看'
  if (message.type === 'request.error') { if (message.requestId === pendingRequest) { pendingRequest = ''; stopOnAck = false }; status.value = `请求失败：${message.data?.code || 'UNKNOWN'}` }
}
async function playNext() {
  if (playing || !queue.length) return
  playing = true; const message = queue.shift(); active = message; const current = generation; mediaAbort = new AbortController()
  let response: Response
  try { response = await fetch(`/api/v1/runtime/media/${message.data.mediaId}`, { headers: { Authorization: `Bearer ${props.token}` }, cache: 'no-store', signal: mediaAbort.signal }) } catch { if (current === generation) finish('', 'FAILED', '音频读取失败'); return }
  if (current !== generation || message.turnId !== turnId.value) return
  if (!response.ok) { finish('', 'FAILED', '音频已不可用'); return }
  let blob: Blob; try { blob = await response.blob() } catch { if (current === generation) finish('', 'FAILED', '音频读取失败'); return }; if (current !== generation || message.turnId !== turnId.value) return
  const url = URL.createObjectURL(blob); audioUrl = url; audio = new Audio(url)
  audio.onplay = () => { if (current !== generation) return; blocked.value = false; status.value = '正在播放'; send('playback.report', { segmentId: message.data.segmentId, state: 'STARTED' }, { turnId: message.turnId }) }
  audio.onended = () => { if (current === generation) finish(url, 'ENDED', '播放完成') }
  audio.onerror = () => { if (current === generation) finish(url, 'FAILED', '播放失败') }
  player?.playAudio(audio).catch(() => {})
}
function finish(url: string, state: 'ENDED' | 'FAILED', message: string) { if (url) URL.revokeObjectURL(url); audioUrl = ''; if (active) send('playback.report', { segmentId: active.data.segmentId, state }, { turnId: active.turnId }); active = undefined; audio = undefined; playing = false; status.value = state === 'ENDED' ? '等待后续语音' : message; if (state === 'FAILED') { queue.splice(0); player?.stop() } else playNext() }
function resume() { if (audio) player?.playAudio(audio).catch(() => {}); blocked.value = false }
function submit() {
  if (!text.value.trim()) return
  const data: Record<string, unknown> = { text: text.value.trim() }
  if (props.mode === 'CHAT' && contextMode.value === 'EXPLICIT') data.contextRequest = { source: contextSource.value, coverage: contextCoverage.value, resultMode: contextResultMode.value }
  if (props.mode === 'CHAT' && contextMode.value === 'AI_ON_DEMAND') data.contextMode = 'AI_ON_DEMAND'
  pendingRequest = send(props.mode === 'CHAT' ? 'chat.create' : 'speech.create', data); stopOnAck = false
}
async function captureContext(message: any) {
  contextAbort?.abort(); const controller = new AbortController(); contextAbort = controller
  try {
    pageContext ??= new (await import('@ln-avatar/sdk/context')).PageContext()
    const result = await pageContext.capture(message.data as ContextRequest, epoch, controller.signal)
    if (controller.signal.aborted || message.turnId !== turnId.value) return
    const form = new FormData(); form.append('metadata', new Blob([JSON.stringify(result.metadata)], { type: 'application/json' }))
    result.images.forEach((blob, index) => form.append(`screenshot${index}`, blob, `screenshot${index}.jpg`))
    const response = await fetch('/api/v1/runtime/context-captures', { method: 'POST', headers: { Authorization: `Bearer ${props.token}`, 'X-Connection-Epoch': epoch }, body: form, signal: controller.signal })
    const body = await response.json(); if (!response.ok || body.code !== 'OK') throw new Error(String(body.error?.code || 'CONTEXT_CAPTURE_FAILED'))
  } catch (error) { if (!controller.signal.aborted) status.value = `页面采集失败：${error instanceof Error ? error.message : 'UNKNOWN'}` }
  finally { if (contextAbort === controller) contextAbort = undefined }
}
function clearAudio() { generation++; queue.splice(0); mediaAbort?.abort(); mediaAbort = undefined; contextAbort?.abort(); pageContext?.clear(); audio?.pause(); audio = undefined; if (audioUrl) URL.revokeObjectURL(audioUrl); audioUrl = ''; active = undefined; blocked.value = false; playing = false; player?.stop() }
function stop() { cancelRecording(); if (pendingRequest) stopOnAck = true; clearAudio(); if (turnId.value) send('turn.stop', { reason: 'USER_STOP' }, { turnId: turnId.value }); turnId.value = ''; status.value = '已停止' }
async function startRecording() {
  const current = recordingGeneration
  try {
    const mimeType = ['audio/webm', 'audio/mp4', 'audio/ogg'].find(type => MediaRecorder.isTypeSupported(type)); if (!mimeType) throw new Error('浏览器不支持录音格式')
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true }); if (current !== recordingGeneration) { stream.getTracks().forEach(track => track.stop()); return }; microphone = stream; recorder = new MediaRecorder(stream, { mimeType }); chunks = []
    recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data) }; recorder.start(); recording.value = true; status.value = '正在录音'
  } catch { microphone?.getTracks().forEach(track => track.stop()); status.value = '无法使用麦克风，请检查浏览器权限' }
}
async function finishRecording() {
  if (!recorder || recorder.state === 'inactive') return
  const current = recorder; const currentGeneration = recordingGeneration; recording.value = false; recognizing.value = true
  const blob = await new Promise<Blob>(resolve => { current.onstop = () => resolve(new Blob(chunks, { type: current.mimeType })); current.stop() })
  microphone?.getTracks().forEach(track => track.stop()); microphone = undefined; recorder = undefined; chunks = []
  if (currentGeneration !== recordingGeneration) return
  if (!blob.size || blob.size > 1_900_000) { recognizing.value = false; status.value = '录音需小于 1.9 MB'; return }
  const form = new FormData(); form.append('audio', blob, 'recording'); const controller = new AbortController(); asrAbort = controller
  try {
    const response = await fetch('/api/v1/runtime/asr', { method: 'POST', headers: { Authorization: `Bearer ${props.token}`, 'Idempotency-Key': crypto.randomUUID() }, body: form, signal: controller.signal })
    const result = await response.json(); if (!response.ok || result.code !== 'OK') throw new Error(String(result.error?.code || 'ASR_FAILED'))
    if (!controller.signal.aborted) { text.value = String(result.data.text || ''); status.value = '识别完成，请确认文字后发送' }
  } catch { if (!controller.signal.aborted) status.value = '识别失败，请重试' }
  finally { if (asrAbort === controller) asrAbort = undefined; recognizing.value = false }
}
function cancelRecording() { recordingGeneration++; asrAbort?.abort(); asrAbort = undefined; if (recorder?.state !== 'inactive') recorder?.stop(); microphone?.getTracks().forEach(track => track.stop()); microphone = undefined; recorder = undefined; chunks = []; recording.value = false; recognizing.value = false }
onMounted(connect)
onUnmounted(() => { stop(); socket?.close(); player?.destroy() })
</script>
<style scoped>.avatar { min-height: 180px; display:flex; justify-content:center; }.avatar :deep(canvas) { max-height:260px; max-width:100%; }.actions { display:flex; align-items:center; gap:8px; margin-top:8px; }.actions span { color:var(--el-text-color-secondary); font-size:13px; }</style>
