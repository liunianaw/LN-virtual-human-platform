import { AvatarSdkError } from "./errors.js";
import {
  type AvatarAction,
  type AvatarActionCode,
  type AvatarAsset,
  type AvatarManifest,
  isAvatarActionCode,
  parseAvatarManifest,
} from "./manifest.js";

export interface AvatarPlayerOptions {
  container: HTMLElement;
  canvas?: HTMLCanvasElement;
}

export interface AvatarPlayerEvents {
  "action.started": { action: AvatarActionCode };
  "action.ended": { action: AvatarActionCode };
  "audio.started": void;
  "audio.ended": void;
  "audio.blocked": { error: unknown };
  error: { error: AvatarSdkError };
}

type EventName = keyof AvatarPlayerEvents;
type EventListener<K extends EventName> = (detail: AvatarPlayerEvents[K]) => void;

const MAX_ACTION_QUEUE = 8;

/**
 * A standalone Canvas player. It intentionally owns no runtime connection or
 * TTS fetching; callers attach the HTMLAudioElement for each authorized audio
 * segment and browser playback events drive the speaking action.
 */
export class AvatarPlayer {
  readonly canvas: HTMLCanvasElement;
  private readonly context: CanvasRenderingContext2D;
  private readonly listeners = new Map<EventName, Set<EventListener<EventName>>>();
  private readonly atlases = new Map<AvatarActionCode, HTMLImageElement>();
  private readonly queue: AvatarActionCode[] = [];
  private manifest?: AvatarManifest;
  private current: AvatarActionCode = "idle";
  private actionStartedAt = 0;
  private animationFrame?: number;
  private audio?: HTMLAudioElement;
  private audioUnbind?: () => void;
  private audioEpoch = 0;
  private audioActive = false;
  private destroyed = false;

  constructor(options: AvatarPlayerOptions) {
    this.canvas = options.canvas ?? document.createElement("canvas");
    this.canvas.width = 512;
    this.canvas.height = 768;
    if (!options.canvas) options.container.append(this.canvas);
    const context = this.canvas.getContext("2d");
    if (!context) throw new AvatarSdkError("ATLAS_LOAD_FAILED", "Canvas 2D is unavailable in this browser");
    this.context = context;
  }

  on<K extends EventName>(event: K, listener: EventListener<K>): () => void {
    const group = this.listeners.get(event) ?? new Set<EventListener<EventName>>();
    group.add(listener as EventListener<EventName>);
    this.listeners.set(event, group);
    return () => group.delete(listener as EventListener<EventName>);
  }

  getActions(): readonly AvatarActionCode[] {
    return this.manifest?.actions.map((action) => action.code) ?? [];
  }

  get currentAction(): AvatarActionCode {
    return this.current;
  }

  /** Validates the complete formal package before requesting any atlas. */
  async loadPackage(rawManifest: unknown): Promise<AvatarManifest> {
    this.assertAvailable();
    const manifest = parseAvatarManifest(rawManifest);
    const now = Date.now();
    for (const asset of [manifest.preview, manifest.baseImage, ...manifest.actions.map((action) => action.atlas)]) {
      if (Date.parse(asset.expiresAt) <= now) {
        throw new AvatarSdkError("ASSET_EXPIRED", `Asset ${asset.fileId} has expired; refresh the runtime package.`);
      }
    }
    const loadedAtlases = await Promise.all(manifest.actions.map(async (action) => [action.code, await loadAtlas(action)] as const));
    await Promise.all([verifyAsset(manifest.preview), verifyAsset(manifest.baseImage)]);
    this.assertAvailable();
    this.stop();
    this.manifest = manifest;
    this.atlases.clear();
    loadedAtlases.forEach(([code, image]) => this.atlases.set(code, image));
    this.queue.splice(0);
    this.audioActive = false;
    this.select("idle");
    return manifest;
  }

  playAction(code: string): void {
    this.assertAvailable();
    this.requireLoaded();
    if (!isAvatarActionCode(code) || !this.atlases.has(code)) {
      throw new AvatarSdkError("ACTION_NOT_SUPPORTED", `Action ${code} is not available in this Avatar package.`);
    }
    if (this.audioActive) {
      if (this.queue.length >= MAX_ACTION_QUEUE) {
        throw new AvatarSdkError("ACTION_QUEUE_FULL", `The action queue is limited to ${MAX_ACTION_QUEUE} items.`);
      }
      this.queue.push(code);
      return;
    }
    this.select(code);
  }

  /**
   * Binds one authorized audio segment. Only a browser `playing` event enters
   * speaking; pause and waiting render idle while preserving audio priority.
   */
  bindAudio(audio: HTMLAudioElement): () => void {
    this.assertAvailable();
    this.requireLoaded();
    this.audioUnbind?.();
    const epoch = ++this.audioEpoch;
    this.audio = audio;
    this.audioActive = true;
    const onPlaying = () => this.handleAudio("playing", epoch);
    const onPaused = () => this.handleAudio("pause", epoch);
    const onWaiting = () => this.handleAudio("waiting", epoch);
    const onEnded = () => this.handleAudio("ended", epoch);
    const onError = () => this.handleAudio("error", epoch);
    audio.addEventListener("playing", onPlaying);
    audio.addEventListener("pause", onPaused);
    audio.addEventListener("waiting", onWaiting);
    audio.addEventListener("ended", onEnded);
    audio.addEventListener("error", onError);
    const unbind = () => {
      audio.removeEventListener("playing", onPlaying);
      audio.removeEventListener("pause", onPaused);
      audio.removeEventListener("waiting", onWaiting);
      audio.removeEventListener("ended", onEnded);
      audio.removeEventListener("error", onError);
      if (this.audio === audio && this.audioEpoch === epoch) {
        this.audio = undefined;
        this.audioActive = false;
      }
    };
    this.audioUnbind = unbind;
    return () => {
      unbind();
      if (this.audioUnbind === unbind) this.audioUnbind = undefined;
    };
  }

  async playAudio(audio: HTMLAudioElement): Promise<void> {
    this.bindAudio(audio);
    const epoch = this.audioEpoch;
    try {
      await audio.play();
    } catch (error) {
      if (epoch === this.audioEpoch && !this.destroyed) {
        this.emit("audio.blocked", { error });
        this.emit("error", { error: new AvatarSdkError("AUDIO_PLAYBACK_BLOCKED", "Browser blocked audio playback.", error) });
      }
    }
  }

  /** Stops local audio now, clears queued actions, and invalidates late events. */
  stop(): void {
    if (this.destroyed) return;
    ++this.audioEpoch;
    const unbind = this.audioUnbind;
    this.audioUnbind = undefined;
    unbind?.();
    const activeAudio = this.audio;
    this.audio = undefined;
    this.audioActive = false;
    this.queue.splice(0);
    if (activeAudio) {
      activeAudio.pause();
      try { activeAudio.currentTime = 0; } catch { /* a media stream may not be seekable */ }
    }
    if (this.manifest) this.select("idle");
  }

  destroy(): void {
    if (this.destroyed) return;
    this.stop();
    this.destroyed = true;
    if (this.animationFrame !== undefined) cancelAnimationFrame(this.animationFrame);
    this.listeners.clear();
    this.atlases.clear();
    this.canvas.remove();
  }

  private handleAudio(event: "playing" | "pause" | "waiting" | "ended" | "error", epoch: number): void {
    if (this.destroyed || epoch !== this.audioEpoch) return;
    if (event === "playing") {
      this.select("speaking");
      this.emit("audio.started", undefined);
      return;
    }
    if (event === "pause" || event === "waiting") {
      this.select("idle");
      return;
    }
    this.audioActive = false;
    this.audio = undefined;
    const unbind = this.audioUnbind;
    this.audioUnbind = undefined;
    unbind?.();
    this.emit("audio.ended", undefined);
    this.select(this.queue.shift() ?? "idle");
  }

  private select(action: AvatarActionCode): void {
    if (!this.manifest) return;
    this.current = action;
    this.actionStartedAt = performance.now();
    this.emit("action.started", { action });
    this.scheduleFrame();
  }

  private scheduleFrame(): void {
    if (this.animationFrame !== undefined) cancelAnimationFrame(this.animationFrame);
    const render = (now: number) => {
      if (this.destroyed || !this.manifest) return;
      const action = this.action(this.current);
      const elapsedFrames = Math.floor((now - this.actionStartedAt) / (1000 / action.fps));
      if (!action.loop && elapsedFrames >= action.frameCount) {
        const ended = this.current;
        this.emit("action.ended", { action: ended });
        this.select("idle");
        return;
      }
      const frame = action.frames[action.loop ? elapsedFrames % action.frameCount : elapsedFrames];
      const atlas = this.atlases.get(action.code);
      if (frame && atlas) {
        this.context.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.context.drawImage(atlas, frame.x, frame.y, frame.width, frame.height, 0, 0, this.canvas.width, this.canvas.height);
      }
      this.animationFrame = requestAnimationFrame(render);
    };
    this.animationFrame = requestAnimationFrame(render);
  }

  private action(code: AvatarActionCode): AvatarAction {
    const action = this.manifest?.actions.find((candidate) => candidate.code === code);
    if (!action) throw new AvatarSdkError("ACTION_NOT_SUPPORTED", `Action ${code} is not loaded.`);
    return action;
  }

  private requireLoaded(): void {
    if (!this.manifest) throw new AvatarSdkError("MANIFEST_INVALID", "Load a formal Avatar package before playing it.");
  }

  private assertAvailable(): void {
    if (this.destroyed) throw new AvatarSdkError("PLAYER_DESTROYED", "This Avatar player has been destroyed.");
  }

  private emit<K extends EventName>(event: K, detail: AvatarPlayerEvents[K]): void {
    this.listeners.get(event)?.forEach((listener) => listener(detail as never));
  }
}

/** Creates the minimal browser-side Avatar SDK instance described by API-01. */
export function createAvatar(options: AvatarPlayerOptions): AvatarPlayer {
  return new AvatarPlayer(options);
}

async function loadAtlas(action: AvatarAction): Promise<HTMLImageElement> {
  const blob = await fetchAsset(action.atlas);
  return new Promise((resolve, reject) => {
    const image = new Image();
    const blobUrl = URL.createObjectURL(blob);
    image.onload = () => {
      URL.revokeObjectURL(blobUrl);
      if (image.naturalWidth !== 1536 || image.naturalHeight !== 1536) {
        reject(new AvatarSdkError("ATLAS_LOAD_FAILED", `Atlas for ${action.code} must be 1536x1536.`));
        return;
      }
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(blobUrl);
      reject(new AvatarSdkError("ATLAS_LOAD_FAILED", `Could not load atlas for ${action.code}.`));
    };
    image.src = blobUrl;
  });
}

async function verifyAsset(asset: AvatarAsset): Promise<void> {
  await fetchAsset(asset);
}

async function fetchAsset(asset: AvatarAsset): Promise<Blob> {
  let response: Response;
  try {
    response = await fetch(asset.url, { credentials: "omit" });
  } catch (error) {
    throw new AvatarSdkError("ATLAS_LOAD_FAILED", `Could not fetch asset ${asset.fileId}.`, error);
  }
  if (!response.ok) {
    throw new AvatarSdkError("ATLAS_LOAD_FAILED", `Could not fetch asset ${asset.fileId}: HTTP ${response.status}.`);
  }
  const buffer = await response.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  const actualHash = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
  if (actualHash !== asset.sha256) {
    throw new AvatarSdkError("ASSET_HASH_MISMATCH", `Asset ${asset.fileId} does not match its package SHA-256.`);
  }
  return new Blob([buffer], { type: response.headers.get("content-type") ?? "application/octet-stream" });
}
