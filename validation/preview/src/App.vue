<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { Player } from './player.mjs';

type Manifest = { action: string; width: number; height: number; fps: number; loop: boolean;
  frames: string[]; frame_hashes: string[]; warnings: string[]; visual_approved: boolean };
type Clip = { manifest: Manifest; images: HTMLImageElement[] };
const names: Record<string, string> = { idle: '待机', wave: '挥手', speaking: '说话', listening: '倾听', thinking: '思考', nod: '点头', shake_head: '摇头', happy: '开心' };
const player = reactive(new Player());
const clips = reactive<Record<string, Clip>>({});
const canvas = ref<HTMLCanvasElement>();
const background = ref('paper');
const error = ref('');
const message = ref('导入素材后，逐项检查角色与动作。');
const frameIndex = ref(0);
const paused = ref(false);
const audioName = ref('');
const audioRef = ref<HTMLAudioElement>();
const audioPaused = ref(true);
let audioURL = '';
let raf = 0;
let lastRevision = -1;
let start = 0;
let loadRevision = 0;
const clip = computed(() => clips[player.current]);
const count = computed(() => Object.keys(clips).length);

async function digest(file: File) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await file.arrayBuffer())))
    .map(v => v.toString(16).padStart(2, '0')).join('');
}
async function importFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  input.value = '';
  if (!files.length) return;
  const revision = ++loadRevision;
  error.value = '';
  const manifestFile = files.find(f => f.name === 'manifest.json');
  try {
    if (!manifestFile || manifestFile.size > 100000) throw new Error('请选择一个动作的 manifest.json 和六张 PNG 帧。');
    const m: Manifest = JSON.parse(await manifestFile.text());
    if (!(m.action in names) || m.width !== 512 || m.height !== 768 || m.fps !== 6 ||
        !Array.isArray(m.frames) || m.frames.length !== 6 || new Set(m.frames).size !== 6 ||
        !Array.isArray(m.frame_hashes) || m.frame_hashes.length !== 6 || !Array.isArray(m.warnings) ||
        typeof m.loop !== 'boolean') throw new Error('素材规格无效，需要本工具输出的六帧素材。');
    const images: HTMLImageElement[] = [];
    for (let i=0; i<6; i++) {
      const frame = files.find(f => f.name === m.frames[i]);
      if (!frame || frame.size > 8*1024*1024) throw new Error('缺少帧文件或单帧超过8MB。');
      if (await digest(frame) !== m.frame_hashes[i]) throw new Error('帧文件校验失败，素材可能已修改。');
      const url = URL.createObjectURL(frame);
      const image = new Image();
      try { image.src = url; await image.decode(); } finally { URL.revokeObjectURL(url); }
      if (image.naturalWidth !== 512 || image.naturalHeight !== 768) throw new Error('帧尺寸不正确。');
      images.push(image);
    }
    if (revision !== loadRevision) return;
    stop();
    clips[m.action] = { manifest: m, images };
    player.action(m.action);
    message.value = `已导入${names[m.action]} · 文件仅在本页读取，不上传。`;
  } catch (e) { error.value = e instanceof Error ? e.message : '素材读取失败'; }
}
function choose(action: string) {
  if (!clips[action]) return;
  paused.value = false;
  player.action(action);
}
function stop() {
  player.stop();
  audioRef.value?.pause();
  if (audioRef.value) audioRef.value.currentTime = 0;
  paused.value = false;
  frameIndex.value = 0;
  audioPaused.value = true;
}
function step() {
  if (player.speech) stop();
  paused.value = true;
  frameIndex.value = (frameIndex.value + 1) % 6;
}
function togglePause() {
  if (player.speech) { audioRef.value?.pause(); return; }
  paused.value = !paused.value;
  start = performance.now() - frameIndex.value * 1000/6;
}
function selectAudio(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  stop();
  if (audioURL) URL.revokeObjectURL(audioURL);
  audioURL = URL.createObjectURL(file);
  audioName.value = file.name;
}
async function playAudio() {
  if (!audioURL) return;
  if (!clips.idle || !clips.speaking) { error.value = '请先导入待机和说话素材。'; return; }
  error.value = '';
  stop();
  const epoch = player.beginAudio();
  const audio = new Audio(audioURL);
  audioRef.value = audio;
  for (const name of ['playing','pause','waiting','ended','error']) {
    audio.addEventListener(name, () => {
      player.audio(name, epoch);
      if (epoch === player.epoch) audioPaused.value = name !== 'playing';
    });
  }
  paused.value = false;
  try { await audio.play(); } catch { player.stop(); error.value = '音频无法播放，请检查格式或浏览器权限。'; }
}
async function toggleAudio() {
  if (!audioRef.value || !player.speech) return;
  if (audioRef.value.paused) {
    try { await audioRef.value.play(); } catch { stop(); error.value = '音频恢复失败。'; }
  } else audioRef.value.pause();
}
function tick(now: number) {
  if (lastRevision !== player.revision) {
    start = now; frameIndex.value = 0; lastRevision = player.revision;
  }
  const current = clip.value;
  const context = canvas.value?.getContext('2d');
  if (current && !paused.value) {
    const frame = Math.floor((now-start)*current.manifest.fps/1000);
    if (frame >= 6 && !current.manifest.loop && !player.speech) player.complete();
    else frameIndex.value = frame % 6;
  }
  if (context) {
    context.clearRect(0, 0, 512, 768);
    const image = clip.value?.images[frameIndex.value];
    if (image) context.drawImage(image, 0, 0, 512, 768);
  }
  raf = requestAnimationFrame(tick);
}
onMounted(() => { raf = requestAnimationFrame(tick); });
onBeforeUnmount(() => { loadRevision++; stop(); cancelAnimationFrame(raf); if (audioURL) URL.revokeObjectURL(audioURL); });
</script>

<template>
  <div class="lab">
    <header><a class="brand" href="/">LN<span>AVATAR LAB</span></a><span class="local-tag"><i></i> 本地制作验证</span></header>
    <main>
      <section class="intro"><p class="eyebrow">PRODUCTION STUDY / 01</p><h1>让角色，<br>从一张图开始。</h1><p class="description">检查每一个表情、动作与透明边缘。<br>素材生成完成后，在这里进行真实播放验证。</p><div class="progress"><strong>{{ count }}<span> / 8</span></strong><p>基础动作已导入</p></div>
        <label class="import-button">＋ 导入一个动作<input type="file" multiple accept=".json,.png" @change="importFiles"></label><p class="hint">选择同一 package 文件夹内的<br>manifest.json 和全部六张 PNG。</p>
      </section>
      <section class="viewer" aria-label="角色预览">
        <div class="stage" :class="background"><div class="stage-top"><span>{{ names[player.current] || '待机' }}</span><span>{{ clip ? 'FRAME '+String(frameIndex+1).padStart(2,'0')+' / 06' : 'WAITING FOR ASSETS' }}</span></div>
          <canvas ref="canvas" width="512" height="768" aria-label="虚拟人动画"></canvas>
          <div v-if="!clip" class="empty"><div class="outline-person"><span></span></div><h2>{{ count ? '当前动作尚未导入' : '角色即将登场' }}</h2><p>{{ count ? '请导入待机素材，或选择已导入动作。' : '先导入第一组生成素材' }}</p></div>
          <div class="stage-bottom"><span>512 × 768</span><div class="swatches" aria-label="预览背景"><button v-for="bg in ['paper','dark','checker']" :key="bg" :class="[bg,{selected:background===bg}]" :aria-label="'切换背景 '+bg" @click="background=bg"></button></div></div>
        </div>
        <div class="transport"><button :disabled="!clip || player.speech" @click="togglePause">{{ paused ? '▶ 继续' : 'Ⅱ 暂停' }}</button><button :disabled="!clip || player.speech" @click="step">下一帧 →</button><button @click="stop">■ 停止</button><span>{{ player.queue.length ? '等待动作 '+player.queue.length : '单动作播放' }}</span></div>
      </section>
      <aside>
        <section class="panel"><div class="panel-heading"><h2>动作库</h2><span>01—08</span></div><div class="action-grid"><button v-for="(label,key,index) in names" :key="key" :disabled="!clips[key]" :class="{active:player.current===key&&clips[key]}" @click="choose(key)"><small>0{{ index+1 }}</small>{{ label }}<i>{{ clips[key] ? '●' : '○' }}</i></button></div></section>
        <section class="panel audio-panel"><div class="panel-heading"><h2>说话联动</h2><span>AUDIO</span></div><p>使用本地音频，检查播放、暂停和结束时的动作切换。</p><label class="audio-picker">{{ audioName || '选择音频文件 ↗' }}<input type="file" accept="audio/*" @change="selectAudio"></label><button class="audio-play" :disabled="!audioName" @click="playAudio">播放并测试</button><button class="audio-play" :disabled="!player.speech" @click="toggleAudio">{{ audioPaused ? '继续音频' : '暂停音频' }}</button><small>基础开合口动画，不做音素口型同步。</small></section>
        <section class="review-note"><h2>检查提示</h2><p>切换深浅背景检查发丝和衣服边缘；逐帧查看手部与人物位置，留意循环首尾是否跳动。</p><span v-if="clip">{{ clip.manifest.visual_approved ? '已有人工验收记录' : '待人工视觉验收' }}</span><p v-if="clip?.manifest.warnings.length" class="warning">自动检查提示：{{ clip.manifest.warnings.join('、') }}</p></section>
      </aside>
    </main>
    <footer><span role="status">{{ message }}</span><span>不上传文件 · 不保存密钥</span></footer>
    <div v-if="error" class="error" role="alert">{{ error }}<button aria-label="关闭提示" @click="error=''">×</button></div>
  </div>
</template>
