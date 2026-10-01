import { AvatarPlayer } from "./avatar-player.js";
import type { CaptureOptions, ContextCapture, PageContext } from "./context.js";

export interface SessionToken { token: string; expiresAt: string }
export interface SessionClientOptions {
  baseUrl: string;
  getToken: () => Promise<SessionToken>;
  player: AvatarPlayer;
}
export interface SessionClientEvent {
  type: string;
  requestId?: string;
  turnId?: string;
  code?: string;
  message?: string;
  data?: unknown;
}
export class SessionClientError extends Error {
  readonly name = "SessionClientError";
  constructor(readonly code: string, message: string, readonly requestId?: string, readonly turnId?: string) {
    super(message);
  }
}
interface Envelope {
  v: number; type: string; requestId: string; sessionId: string;
  connectionEpoch: string; turnId: string | null; seq: string;
  occurredAt: string; data: Record<string, unknown>;
}
export interface SessionState {
  sessionId: string; applicationId: string;
  status: string; expiresAt: string; connectionEpoch: string;
  effectiveScopes: string[]; activeTurn: { turnId: string } | null;
}
interface Ticket { ticket: string; expiresAt: string; webSocketUrl: string; protocol: string }
interface AudioSegment { segmentId: string; ordinal: number; mediaId: string; expiresAt: string }

/** Browser-only short grant client. The callback belongs to the developer backend; it never accepts an Application Secret. */
export class SessionClient {
  private readonly listeners = new Set<(event: SessionClientEvent) => void>();
  private readonly baseUrl: string;
  private readonly player: AvatarPlayer;
  private readonly getToken: SessionClientOptions["getToken"];
  private socket?: WebSocket;
  private token?: SessionToken;
  private session?: SessionState;
  private epoch?: string;
  private activeTurn?: string;
  private readonly stoppedTurns = new Set<string>();
  private readonly pendingStop = new Set<string>();
  private readonly pendingSpeech = new Set<string>();
  private readonly stopOnAck = new Map<string, string>();
  private readonly sequence = new Map<string, bigint>();
  private readonly audioQueue = new Map<number, AudioSegment>();
  private nextOrdinal = 0;
  private playing = false;
  private audio?: HTMLAudioElement;
  private audioUrl?: string;
  private mediaAbort?: AbortController;
  private asrAbort?: AbortController;
  private contextAbort?: AbortController;
  private pageContext?: PageContext;
  private recording?: { recorder: MediaRecorder; stream: MediaStream; chunks: Blob[]; cancelled: boolean };
  private recordingPending = false;
  private recordingGeneration = 0;
  private readonly requests = new Set<AbortController>();
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private heartbeatTimer?: ReturnType<typeof setInterval>;
  private generation = 0;
  private reconnects = 0;
  private destroyed = false;
  private connecting?: Promise<SessionState>;
  private reauthorization?: { requestId: string; resolve: () => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> };

  constructor(options: SessionClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.getToken = options.getToken;
    this.player = options.player;
    this.player.on("audio.blocked", () => this.emit({ type: "error", code: "AUTOPLAY_BLOCKED", message: "Click to continue audio playback." }));
    this.player.on("error", ({ error }) => this.emit({ type: "error", code: error.code, message: error.message }));
  }

  on(listener: (event: SessionClientEvent) => void): () => void {
    this.assertLive();
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  connect(): Promise<SessionState> {
    this.assertLive();
    if (this.connecting) return this.connecting;
    if (this.session && this.epoch && this.socketOpen()) return Promise.resolve(this.session);
    this.connecting = this.open().catch((error: unknown) => {
      this.emitError(error);
      if (this.reconnects > 0 && !this.socket && !this.destroyed) this.retry();
      throw error;
    }).finally(() => { this.connecting = undefined; });
    return this.connecting;
  }

  speak(text: string): string {
    if (!text.trim()) throw new SessionClientError("INVALID_ARGUMENT", "Speech text is required.");
    const requestId = this.command("speech.create", { text });
    this.pendingSpeech.add(requestId);
    return requestId;
  }

  async capture(options: CaptureOptions): Promise<ContextCapture> {
    this.assertLive();
    if (!this.session?.effectiveScopes.includes("context:capture"))
      throw new SessionClientError("CONTEXT_NOT_ALLOWED", "Connect the Session before capture.");
    const controller = new AbortController();
    this.contextAbort?.abort();
    this.contextAbort = controller;
    this.pageContext ??= new (await import("./context.js")).PageContext();
    try {
      const result = await this.pageContext.captureLocal(options, controller.signal);
      this.assertLive();
      if (controller.signal.aborted || this.contextAbort !== controller)
        throw new SessionClientError("CONTEXT_CANCELLED", "Capture was superseded.");
      return result;
    }
    finally { if (this.contextAbort === controller) this.contextAbort = undefined; }
  }

  highlight(captureRequestId: string, elementRef: string): string {
    return this.pageContext?.highlight(captureRequestId, elementRef) ?? "TARGET_STALE";
  }

  clearContextHighlight(): void { this.pageContext?.clearHighlight(); }

  async startRecording(): Promise<void> {
    this.assertLive();
    if (this.recording || this.recordingPending) throw new SessionClientError("RECORDING_ACTIVE", "Recording is already active.");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined")
      throw new SessionClientError("RECORDING_UNAVAILABLE", "Browser recording is unavailable.");
    const mimeType = ["audio/webm", "audio/mp4", "audio/ogg"].find(type => MediaRecorder.isTypeSupported(type));
    if (!mimeType) throw new SessionClientError("RECORDING_FORMAT_UNSUPPORTED", "No supported recording format is available.");
    const generation = this.recordingGeneration;
    this.recordingPending = true;
    let stream: MediaStream;
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
    catch { this.emit({ type: "recording.permission-denied" }); throw new SessionClientError("MICROPHONE_DENIED", "Microphone permission was denied."); }
    finally { this.recordingPending = false; }
    if (this.destroyed || generation !== this.recordingGeneration) {
      stream.getTracks().forEach(track => track.stop());
      throw new SessionClientError("RECORDING_CANCELLED", "Recording was cancelled.");
    }
    let recorder: MediaRecorder;
    try { recorder = new MediaRecorder(stream, { mimeType }); }
    catch { stream.getTracks().forEach(track => track.stop()); throw new SessionClientError("RECORDING_FORMAT_UNSUPPORTED", "Recording format could not be started."); }
    const recording = { recorder, stream, chunks: [] as Blob[], cancelled: false };
    recorder.ondataavailable = event => { if (!recording.cancelled && event.data.size) recording.chunks.push(event.data); };
    this.recording = recording;
    try { recorder.start(); }
    catch { this.recording = undefined; stream.getTracks().forEach(track => track.stop()); throw new SessionClientError("RECORDING_FAILED", "Recording could not start."); }
    this.emit({ type: "recording.started", data: { mimeType } });
  }

  async endRecording(): Promise<string> {
    this.assertLive();
    const recording = this.recording;
    if (!recording || recording.recorder.state === "inactive") throw new SessionClientError("RECORDING_NOT_ACTIVE", "No recording is active.");
    const blob = await new Promise<Blob>((resolve, reject) => {
      recording.recorder.onerror = () => reject(new SessionClientError("RECORDING_FAILED", "Recording failed."));
      recording.recorder.onstop = () => resolve(new Blob(recording.chunks, { type: recording.recorder.mimeType }));
      recording.recorder.stop();
    }).finally(() => { if (this.recording === recording) this.recording = undefined; recording.stream.getTracks().forEach(track => track.stop()); });
    if (recording.cancelled) throw new SessionClientError("RECORDING_CANCELLED", "Recording was cancelled.");
    if (!blob.size || blob.size > 1_900_000) throw new SessionClientError("RECORDING_SIZE_INVALID", "Recording must be at most 1.9 MB.");
    this.emit({ type: "recording.ended", data: { bytes: blob.size } });
    const requestId = this.requestId();
    const form = new FormData();
    form.append("audio", blob, "recording");
    const controller = new AbortController();
    this.asrAbort = controller;
    try {
      if (!this.token) throw new SessionClientError("TOKEN_REQUIRED", "Session Token is unavailable.");
      const response = await fetch(`${this.baseUrl}/api/v1/runtime/asr`, { method: "POST", cache: "no-store",
        headers: { Authorization: `Bearer ${this.token.token}`, "Idempotency-Key": requestId },
        body: form, signal: controller.signal });
      const envelope = await response.json() as { code: string; data?: { text: string; operationId: string; language?: string }; error?: { code: string; message: string } };
      if (!response.ok || envelope.code !== "OK" || !envelope.data?.text)
        throw new SessionClientError(envelope.error?.code ?? "ASR_FAILED", envelope.error?.message ?? "Recognition failed.", requestId);
      if (controller.signal.aborted) throw new SessionClientError("RECORDING_CANCELLED", "Recognition was cancelled.", requestId);
      this.emit({ type: "asr.completed", requestId, data: envelope.data });
      return envelope.data.text;
    } catch (error) { this.emitError(error, requestId); throw error; }
    finally { if (this.asrAbort === controller) this.asrAbort = undefined; }
  }

  cancelRecording(): void {
    ++this.recordingGeneration;
    const recording = this.recording;
    if (recording) {
      recording.cancelled = true;
      this.recording = undefined;
      if (recording.recorder.state !== "inactive") recording.recorder.stop();
      recording.stream.getTracks().forEach(track => track.stop());
    }
    this.asrAbort?.abort();
    this.emit({ type: "recording.cancelled" });
  }

  playAction(action: string): void {
    this.assertLive();
    try { this.player.playAction(action); }
    catch (error) { this.emitError(error); throw error; }
  }

  stop(reason = "USER_STOP"): void {
    this.assertLive();
    if (this.recording || this.asrAbort) this.cancelRecording();
    this.contextAbort?.abort();
    const turnId = this.activeTurn;
    for (const requestId of this.pendingSpeech) this.stopOnAck.set(requestId, reason);
    if (turnId) this.pendingStop.add(turnId);
    this.stopLocal();
    if (turnId) this.sendStop(turnId, reason);
  }

  /** Call after a user gesture when the browser blocks automatic playback. */
  async resumeAudio(): Promise<void> {
    this.assertLive();
    if (this.audio?.paused) await this.player.playAudio(this.audio);
  }

  clearHighlight(): void { this.pageContext?.clear(); }

  destroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    this.cancelRecording();
    this.contextAbort?.abort();
    this.pageContext?.clear();
    ++this.generation;
    this.clearTimers();
    if (this.reauthorization) {
      clearTimeout(this.reauthorization.timer);
      this.reauthorization.reject(new SessionClientError("CLIENT_DESTROYED", "SessionClient has been destroyed."));
      this.reauthorization = undefined;
    }
    this.stopLocal();
    for (const request of this.requests) request.abort();
    this.socket?.close(1000, "destroyed");
    this.socket = undefined;
    this.listeners.clear();
    this.pendingStop.clear();
    this.pendingSpeech.clear();
    this.stopOnAck.clear();
    this.player.destroy();
  }

  private async open(): Promise<SessionState> {
    const generation = ++this.generation;
    this.clearTimers();
    this.stopLocal();
    this.pendingStop.clear();
    this.pendingSpeech.clear();
    this.stopOnAck.clear();
    this.socket?.close(1000, "reconnect");
    this.socket = undefined;
    this.epoch = undefined;
    this.sequence.clear();
    this.token = await this.getToken();
    this.guard(generation);
    this.checkToken(this.token);
    const state = await this.request<SessionState>("/session");
    this.guard(generation);
    if (state.status !== "ACTIVE" || !/^\d+$/.test(state.sessionId)) throw new SessionClientError("SESSION_ENDED", "Session is unavailable.");
    if (this.session && this.session.sessionId !== state.sessionId) throw new SessionClientError("SESSION_CHANGED", "Token refers to another Session.");
    this.session = state;
    const manifest = await this.request<unknown>("/avatar-package");
    this.guard(generation);
    await this.player.loadPackage(manifest);
    this.guard(generation);
    const ticket = await this.ticket("CONNECT");
    this.guard(generation);
    await this.openSocket(ticket, generation);
    this.guard(generation);
    if (!this.epoch || !this.socketOpen())
      throw new SessionClientError("CONNECTION_CLOSED", "Connection closed during authentication.");
    this.reconnects = 0;
    this.scheduleRefresh();
    this.heartbeatTimer = setInterval(() => {
      if (this.epoch && this.socket?.readyState === WebSocket.OPEN)
        this.command("ping", { clientTime: new Date().toISOString() });
    }, 20_000);
    return state;
  }

  private async openSocket(ticket: Ticket, generation: number): Promise<void> {
    if (!/^wss?:\/\//.test(ticket.webSocketUrl) || ticket.protocol !== "ln-avatar.v1")
      throw new SessionClientError("TICKET_INVALID", "Invalid WebSocket destination.");
    const socket = new WebSocket(ticket.webSocketUrl, ticket.protocol);
    this.socket = socket;
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        socket.close(4408, "authentication timeout");
        reject(new SessionClientError("CONNECT_TIMEOUT", "Connection authentication timed out."));
      }, 5_000);
      socket.onopen = () => socket.send(JSON.stringify({ v: 1, type: "connection.auth", requestId: this.requestId(), data: { ticket: ticket.ticket } }));
      socket.onmessage = (message) => {
        try {
          const event = JSON.parse(String(message.data)) as Envelope;
          if (generation !== this.generation || this.destroyed) return;
          if (event.type === "connection.ready") {
            if (event.sessionId !== this.session?.sessionId || !/^\d+$/.test(event.connectionEpoch))
              throw new SessionClientError("PROTOCOL_ERROR", "Connection identity mismatch.");
            this.epoch = event.connectionEpoch;
            if (this.session) {
              this.session.connectionEpoch = event.connectionEpoch;
              if (Array.isArray(event.data.effectiveScopes))
                this.session.effectiveScopes = event.data.effectiveScopes.filter((scope): scope is string => typeof scope === "string");
            }
            clearTimeout(timer);
            this.accept(event);
            resolve();
            return;
          }
          if (!this.epoch) return;
          this.accept(event);
        } catch (error) {
          clearTimeout(timer);
          socket.close(4400, "invalid frame");
          reject(error);
        }
      };
      socket.onerror = () => { clearTimeout(timer); reject(new SessionClientError("NETWORK_ERROR", "WebSocket connection failed.")); };
      socket.onclose = (event) => {
        clearTimeout(timer);
        if (!this.epoch) reject(new SessionClientError("CONNECTION_CLOSED", "Connection closed before authentication."));
        if (generation === this.generation && !this.destroyed) this.closed(event.code);
      };
    });
  }

  private accept(event: Envelope): void {
    if (event.v !== 1 || event.sessionId !== this.session?.sessionId || event.connectionEpoch !== this.epoch) return;
    if (!/^\d+$/.test(event.seq)) return;
    const key = event.turnId ?? "connection";
    const seq = BigInt(event.seq);
    if (seq <= (this.sequence.get(key) ?? 0n)) return;
    this.sequence.set(key, seq);
    if (event.turnId && this.stoppedTurns.has(event.turnId)
      && !(this.pendingStop.has(event.turnId) && ["turn.stopped", "request.error"].includes(event.type))) return;
    if (event.turnId && event.type === "turn.stopped") this.pendingStop.delete(event.turnId);
    if (event.type === "connection.reauthorized" && event.requestId === this.reauthorization?.requestId) {
      clearTimeout(this.reauthorization.timer);
      this.reauthorization.resolve();
      this.reauthorization = undefined;
    }
    if (event.type === "request.error" && event.requestId === this.reauthorization?.requestId) {
      clearTimeout(this.reauthorization.timer);
      this.reauthorization.reject(new SessionClientError(String(event.data.code ?? "REAUTHORIZE_FAILED"),
        String(event.data.message ?? "Reauthorization failed."), event.requestId));
      this.reauthorization = undefined;
    }
    if (event.type === "request.ack" && typeof event.data.turnId === "string") {
      this.pendingSpeech.delete(event.requestId);
      const stopReason = this.stopOnAck.get(event.requestId);
      this.stopOnAck.delete(event.requestId);
      if (stopReason !== undefined) {
        this.pendingStop.add(event.data.turnId);
        this.stoppedTurns.add(event.data.turnId);
        this.sendStop(event.data.turnId, stopReason);
      } else {
        if (this.activeTurn && this.activeTurn !== event.data.turnId) this.stopLocal();
        this.activeTurn = event.data.turnId;
        this.nextOrdinal = 0;
      }
    }
    if (event.type === "audio.segment" && event.turnId === this.activeTurn) {
      const item = event.data as unknown as AudioSegment;
      if (Number.isInteger(item.ordinal) && item.ordinal >= this.nextOrdinal) {
        this.audioQueue.set(item.ordinal, item);
        void this.playNext(event.turnId);
      }
    }
    if (event.type === "turn.stopped" || event.type === "turn.completed" || event.type === "turn.failed") {
      if (event.turnId) this.stoppedTurns.add(event.turnId);
      if (event.turnId === this.activeTurn) this.stopLocal();
    }
    if (event.type === "connection.replaced" || event.type === "connection.revoked") {
      this.stopLocal();
      this.socket?.close(4009, "replaced");
    }
    if (event.type === "request.error") {
      this.pendingSpeech.delete(event.requestId);
      this.stopOnAck.delete(event.requestId);
      if (event.turnId) this.pendingStop.delete(event.turnId);
      this.emit({ type: "error", code: String(event.data.code ?? "REQUEST_ERROR"),
        message: String(event.data.message ?? "Request failed."), requestId: event.requestId, turnId: event.turnId ?? undefined });
    }
    this.emit({ type: event.type, requestId: event.requestId, turnId: event.turnId ?? undefined, data: event.data });
  }

  private async playNext(turnId: string): Promise<void> {
    if (this.playing || this.destroyed || turnId !== this.activeTurn) return;
    const item = this.audioQueue.get(this.nextOrdinal);
    if (!item) return;
    this.playing = true;
    this.audioQueue.delete(this.nextOrdinal);
    const generation = this.generation;
    this.mediaAbort = new AbortController();
    try {
      if (Date.parse(item.expiresAt) <= Date.now()) throw new SessionClientError("MEDIA_EXPIRED", "Audio segment expired.", undefined, turnId);
      const response = await fetch(`${this.baseUrl}/api/v1/runtime/media/${encodeURIComponent(item.mediaId)}`,
        { headers: { Authorization: `Bearer ${this.token?.token}` }, cache: "no-store", signal: this.mediaAbort.signal });
      if (!response.ok) throw new SessionClientError("MEDIA_UNAVAILABLE", `Audio HTTP ${response.status}.`, undefined, turnId);
      const blob = await response.blob();
      if (generation !== this.generation || this.stoppedTurns.has(turnId)) return;
      this.audioUrl = URL.createObjectURL(blob);
      const audio = new Audio(this.audioUrl);
      this.audio = audio;
      audio.onplaying = () => { if (this.audio === audio && turnId === this.activeTurn) this.report(item.segmentId, "STARTED", turnId); };
      audio.onended = () => {
        if (this.audio !== audio || turnId !== this.activeTurn) return;
        this.report(item.segmentId, "ENDED", turnId);
        this.releaseAudio();
        this.playing = false;
        ++this.nextOrdinal;
        void this.playNext(turnId);
      };
      audio.onerror = () => {
        if (this.audio !== audio || turnId !== this.activeTurn) return;
        this.report(item.segmentId, "FAILED", turnId);
        this.emit({ type: "error", code: "AUDIO_PLAYBACK_FAILED", turnId });
        this.stopLocal();
      };
      await this.player.playAudio(audio);
    } catch (error) {
      if (generation === this.generation && !this.stoppedTurns.has(turnId)) {
        this.report(item.segmentId, "FAILED", turnId);
        this.emitError(error, undefined, turnId);
        this.stopLocal();
      }
    }
  }


  private report(segmentId: string, state: string, turnId: string): void {
    if (turnId === this.activeTurn && this.socket?.readyState === WebSocket.OPEN)
      this.command("playback.report", { segmentId, state }, turnId);
  }

  private async refresh(): Promise<void> {
    if (!this.epoch || this.destroyed) return;
    try {
      const next = await this.getToken();
      this.checkToken(next);
      if (this.token && Date.parse(next.expiresAt) <= Date.parse(this.token.expiresAt))
        throw new SessionClientError("TOKEN_NOT_RENEWED", "Developer backend has not renewed the Session Token.");
      if (this.destroyed) return;
      this.token = next;
      const ticket = await this.ticket("REAUTHORIZE", this.epoch);
      const requestId = this.command("connection.reauthorize", { ticket: ticket.ticket });
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
          this.reauthorization = undefined;
          reject(new SessionClientError("REAUTHORIZE_TIMEOUT", "Reauthorization timed out.", requestId));
        }, 5_000);
        this.reauthorization = { requestId, resolve, reject, timer };
      });
      this.scheduleRefresh();
    } catch (error) {
      this.emitError(error);
      if (!this.destroyed) this.refreshTimer = setTimeout(() => void this.refresh(), 10_000);
    }
  }

  private closed(code: number): void {
    this.clearTimers();
    if (this.reauthorization) {
      clearTimeout(this.reauthorization.timer);
      this.reauthorization.reject(new SessionClientError("CONNECTION_CLOSED", "Connection closed during reauthorization."));
      this.reauthorization = undefined;
    }
    this.stopLocal();
    this.pendingStop.clear();
    this.pendingSpeech.clear();
    this.stopOnAck.clear();
    this.epoch = undefined;
    this.emit({ type: "connection.disconnected", code: "NETWORK_DISCONNECTED", data: { closeCode: code } });
    if ([4003, 4009, 4010, 4400].includes(code)) {
      this.emit({ type: "error", code: code === 4009 ? "CONNECTION_REPLACED" : "SESSION_UNAVAILABLE" });
      return;
    }
    this.retry();
  }

  private retry(): void {
    if (this.reconnectTimer || this.destroyed) return;
    if (this.reconnects >= 3) { this.emit({ type: "error", code: "RECONNECT_EXHAUSTED" }); return; }
    const delay = (2 ** this.reconnects++) * 1_000 + Math.floor(Math.random() * 300);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      void this.connect().catch(() => { /* connect already emitted the error */ });
    }, delay);
  }
  private async ticket(purpose: "CONNECT" | "REAUTHORIZE", connectionEpoch?: string): Promise<Ticket> {
    return this.request<Ticket>("/connection-tickets", { method: "POST", body: JSON.stringify({ purpose, connectionEpoch }) });
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    if (!this.token) throw new SessionClientError("TOKEN_REQUIRED", "Session Token is unavailable.");
    const controller = new AbortController();
    this.requests.add(controller);
    try {
      const response = await fetch(`${this.baseUrl}/api/v1/runtime${path}`, {
        ...init, cache: "no-store", signal: controller.signal,
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${this.token.token}`, ...init.headers },
      });
      const envelope = await response.json() as { code: string; data: T; error?: { code: string; message: string } };
      if (!response.ok || envelope.code !== "OK") throw new SessionClientError(envelope.error?.code ?? "HTTP_ERROR", envelope.error?.message ?? `HTTP ${response.status}`);
      return envelope.data;
    } finally {
      this.requests.delete(controller);
    }
  }

  private command(type: string, data: Record<string, unknown>, turnId?: string): string {
    this.assertLive();
    if (!this.epoch || this.socket?.readyState !== WebSocket.OPEN)
      throw new SessionClientError("NOT_CONNECTED", "Runtime connection is unavailable.");
    const requestId = this.requestId();
    this.socket.send(JSON.stringify({ v: 1, type, requestId, connectionEpoch: this.epoch, ...(turnId ? { turnId } : {}), data }));
    return requestId;
  }

  private sendStop(turnId: string, reason: string): void {
    if (this.socketOpen() && this.epoch) {
      try { this.command("turn.stop", { reason }, turnId); return; }
      catch (error) { this.emitError(error, undefined, turnId); }
    }
    if (this.token && this.epoch) {
      void this.request("/stop", { method: "POST", headers: { "X-Connection-Epoch": this.epoch }, body: JSON.stringify({ turnId, reason }) })
        .catch((error: unknown) => this.emitError(error, undefined, turnId));
    }
  }
  private socketOpen(): boolean { return this.socket?.readyState === WebSocket.OPEN; }
  private stopLocal(): void {
    this.contextAbort?.abort();
    this.pageContext?.clear();
    if (this.activeTurn) {
      this.stoppedTurns.add(this.activeTurn);
      if (this.stoppedTurns.size > 256) this.stoppedTurns.delete(this.stoppedTurns.values().next().value!);
    }
    this.activeTurn = undefined;
    this.audioQueue.clear();
    this.mediaAbort?.abort();
    this.mediaAbort = undefined;
    this.releaseAudio();
    this.player.stop();
    this.playing = false;
  }
  private releaseAudio(): void {
    if (this.audio) {
      const audio = this.audio;
      this.audio = undefined;
      audio.onplaying = null;
      audio.onended = null;
      audio.onerror = null;
      audio.pause();
      audio.src = "";
    }
    if (this.audioUrl) { URL.revokeObjectURL(this.audioUrl); this.audioUrl = undefined; }
  }
  private clearTimers(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.refreshTimer = undefined; this.reconnectTimer = undefined; this.heartbeatTimer = undefined;
  }
  private checkToken(token: SessionToken): void {
    if (!token?.token || Date.parse(token.expiresAt) <= Date.now() + 5_000)
      throw new SessionClientError("TOKEN_EXPIRED", "Developer backend returned an expired Session Token.");
  }
  private scheduleRefresh(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => void this.refresh(), Math.max(1_000, Date.parse(this.token!.expiresAt) - Date.now() - 60_000));
  }
  private guard(generation: number): void {
    if (this.destroyed || generation !== this.generation) throw new SessionClientError("CLIENT_DESTROYED", "Connection attempt was superseded.");
  }
  private assertLive(): void { if (this.destroyed) throw new SessionClientError("CLIENT_DESTROYED", "SessionClient has been destroyed."); }
  private requestId(): string { return crypto.randomUUID(); }
  private emit(event: SessionClientEvent): void {
    for (const listener of this.listeners) {
      try { listener(event); } catch { /* UI listener failures must not close the runtime connection. */ }
    }
  }
  private emitError(error: unknown, requestId?: string, turnId?: string): void {
    this.emit({ type: "error", code: error instanceof SessionClientError ? error.code
        : error && typeof error === "object" && "code" in error ? String(error.code) : "NETWORK_ERROR",
      message: error instanceof Error ? error.message : "Runtime request failed.",
      requestId: requestId ?? (error instanceof SessionClientError ? error.requestId : undefined),
      turnId: turnId ?? (error instanceof SessionClientError ? error.turnId : undefined) });
  }
}
