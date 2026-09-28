/** Optional browser page capture. Importing the core SDK does not load the renderer. */
export type ContextSource = "PAGE" | "HYBRID";
export type ContextCoverage = "VIEWPORT" | "FULL_PAGE";
export type ContextStatus = "SUCCESS" | "FAILED" | "NOT_REQUESTED";

export interface ContextPolicy {
  enabled: boolean;
  sources: ContextSource[];
  captureScope: { allow: string[]; deny: string[]; allowViewport: boolean };
  dom: { allow: string[]; deny: string[]; excludePassword: true };
  fullPageEnabled: boolean;
  highlightMode: "EVENT_ONLY" | "AUTO";
  allowScroll?: boolean;
}
export interface ContextRequest {
  captureRequestId: string;
  turnId: string;
  source: ContextSource;
  coverage: ContextCoverage;
  resultMode: "PARTIAL" | "STRICT";
  deadlineAt: string;
  fixedPolicy: ContextPolicy;
  currentPolicy: ContextPolicy;
}
export interface ContextCapture {
  metadata: {
    captureRequestId: string; turnId: string; connectionEpoch: string;
    source: ContextSource; coverage: { mode: ContextCoverage; x: number; y: number;
      width: number; height: number; truncated: boolean };
    screenshot: { status: ContextStatus; parts: { field: string; x: number; y: number; width: number; height: number }[]; reason?: string };
    dom: { status: ContextStatus; text?: string; elements?: { ref: string; text: string }[]; excludedCount?: number; reason?: string };
  };
  images: Blob[];
}

function query(selectors: string[]): Element[] {
  return selectors.flatMap(selector => Array.from(document.querySelectorAll(selector)));
}

function within(element: Element, allowed: string[], denied: string[]): boolean {
  if (denied.some(selector => element.closest(selector))) return false;
  return allowed.length === 0 || allowed.some(selector => element.closest(selector));
}

type Box = { left: number; top: number; right: number; bottom: number };
function intersects(rect: Box, box: Box): boolean {
  return rect.right > box.left && rect.bottom > box.top && rect.left < box.right && rect.top < box.bottom;
}

function deniedCapture(request: ContextRequest, connectionEpoch: string): ContextCapture {
  return { metadata: {
    captureRequestId: request.captureRequestId, turnId: request.turnId, connectionEpoch,
    source: request.source, coverage: { mode: request.coverage,
      x: request.coverage === "VIEWPORT" ? Math.max(0, scrollX) : 0,
      y: request.coverage === "VIEWPORT" ? Math.max(0, scrollY) : 0,
      width: 1, height: 1, truncated: true },
    screenshot: { status: "FAILED", parts: [], reason: "CONTEXT_SCOPE_DENIED" },
    dom: request.source === "PAGE" ? { status: "NOT_REQUESTED" }
      : { status: "FAILED", reason: "FILTER_FAILED" },
  }, images: [] };
}

export class PageContext {
  private refs = new Map<string, { element: Element; node: Text; text: string;
    captureRequestId: string; policies: ContextPolicy[];
    root: Element | null; box: Box; coverage: ContextCoverage; scrollX: number; scrollY: number; url: string }>();
  private marked?: { element: HTMLElement; outline: string; outlineOffset: string };

  async capture(request: ContextRequest, connectionEpoch: string, signal?: AbortSignal): Promise<ContextCapture> {
    this.clear();
    if (request.source !== "PAGE" && request.source !== "HYBRID") throw new Error("CONTEXT_SOURCE_DENIED");
    if (Date.parse(request.deadlineAt) <= Date.now()) throw new Error("CONTEXT_TIMEOUT");
    const policies = [request.fixedPolicy, request.currentPolicy];
    if (policies.some(policy => !policy.enabled || !policy.sources.includes(request.source)
      || !policy.captureScope.allowViewport || request.coverage === "FULL_PAGE" && !policy.fullPageEnabled))
      throw new Error("CONTEXT_SCOPE_DENIED");
    const coverage = request.coverage;
    if (coverage !== "VIEWPORT" && coverage !== "FULL_PAGE") throw new Error("CONTEXT_SCOPE_DENIED");
    const allowed = policies.flatMap(policy => policy.captureScope.allow);
    let root: Element | null = null;
    try {
      root = allowed.length ? query(allowed).find(element => policies.every(policy =>
        within(element, policy.captureScope.allow, policy.captureScope.deny))) ?? null : null;
    } catch { return deniedCapture(request, connectionEpoch); }
    if (allowed.length && !root) return deniedCapture(request, connectionEpoch);
    const rect = root?.getBoundingClientRect();
    const box: Box = rect ? { left: Math.max(coverage === "VIEWPORT" ? 0 : -scrollX, rect.left),
      top: Math.max(coverage === "VIEWPORT" ? 0 : -scrollY, rect.top),
      right: Math.min(coverage === "VIEWPORT" ? innerWidth : document.documentElement.scrollWidth - scrollX, rect.right),
      bottom: Math.min(coverage === "VIEWPORT" ? innerHeight : document.documentElement.scrollHeight - scrollY, rect.bottom) }
      : coverage === "VIEWPORT" ? { left: 0, top: 0, right: innerWidth, bottom: innerHeight }
        : { left: -scrollX, top: -scrollY, right: document.documentElement.scrollWidth - scrollX,
          bottom: document.documentElement.scrollHeight - scrollY };
    const totalWidth = Math.floor(box.right - box.left), totalHeight = Math.floor(box.bottom - box.top);
    if (totalWidth < 1 || totalHeight < 1) return deniedCapture(request, connectionEpoch);
    const width = Math.min(4096, totalWidth), height = Math.min(4096, totalHeight);
    const truncated = width < totalWidth || height < totalHeight;
    const metadata: ContextCapture["metadata"] = {
      captureRequestId: request.captureRequestId, turnId: request.turnId, connectionEpoch,
      source: request.source, coverage: { mode: coverage, x: Math.floor(scrollX + box.left),
        y: Math.floor(scrollY + box.top), width, height, truncated },
      screenshot: { status: "FAILED", parts: [] }, dom: { status: request.source === "PAGE" ? "NOT_REQUESTED" : "FAILED" },
    };
    const images: Blob[] = [];
    try {
      for (const policy of policies) {
        if (query(policy.captureScope.deny).some(element => intersects(element.getBoundingClientRect(), box)))
          throw new Error("CONTEXT_SCOPE_DENIED");
      }
      for (const element of Array.from(document.querySelectorAll("img,iframe"))) {
        if (!intersects(element.getBoundingClientRect(), box)) continue;
        const address = element instanceof HTMLImageElement ? element.currentSrc || element.src
          : element instanceof HTMLIFrameElement ? element.src : "";
        if (address && !address.startsWith("data:") && !address.startsWith("blob:")
          && new URL(address, location.href).origin !== location.origin) throw new Error("CROSS_ORIGIN");
      }
      if (signal?.aborted) throw new Error("CONTEXT_CANCELLED");
      const html2canvas = (await import("html2canvas")).default;
      for (let y = 0; y < height; y += 1024) {
        if (signal?.aborted || Date.parse(request.deadlineAt) <= Date.now()) throw new Error("CONTEXT_TIMEOUT");
        const partHeight = Math.min(1024, height - y);
        const canvas = await html2canvas(document.documentElement, {
          x: Math.floor(scrollX + box.left), y: Math.floor(scrollY + box.top) + y,
          width, height: partHeight, scale: 1, useCORS: false, allowTaint: false, logging: false,
          backgroundColor: "#ffffff",
        });
        const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("SCREENSHOT_FAILED")), "image/jpeg", 0.75));
        if (blob.size > 1_200_000 || images.reduce((sum, item) => sum + item.size, 0) + blob.size > 1_200_000)
          throw new Error("PIXEL_LIMIT");
        metadata.screenshot.parts.push({ field: `screenshot${images.length}`, x: 0, y, width, height: partHeight });
        images.push(blob);
      }
      metadata.screenshot.status = "SUCCESS";
    } catch (error) {
      images.length = 0;
      metadata.screenshot.parts = [];
      metadata.screenshot.reason = error instanceof Error
        && ["CONTEXT_SCOPE_DENIED", "CONTEXT_CANCELLED", "CONTEXT_TIMEOUT", "CROSS_ORIGIN", "PIXEL_LIMIT"].includes(error.message)
        ? error.message : "SCREENSHOT_FAILED";
    }
    if (request.source === "HYBRID") {
      try {
        const pieces: string[] = [];
        const elements: { ref: string; text: string }[] = [];
        let bytes = 0, excludedCount = 0;
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
          if (signal?.aborted) throw new Error("CONTEXT_CANCELLED");
          const text = walker.currentNode.textContent?.trim();
          if (!text) continue;
          const element = walker.currentNode.parentElement;
          if (!element || !element.getClientRects().length || root && !root.contains(element)
            || !intersects(element.getBoundingClientRect(), box) || ["SCRIPT", "STYLE", "NOSCRIPT"].includes(element.tagName)
            || policies.some(policy => !within(element, policy.dom.allow, policy.dom.deny)
              || !within(element, policy.captureScope.allow, policy.captureScope.deny)
              || element.closest('input[type="password"]'))) { excludedCount++; continue; }
          const next = new TextEncoder().encode(text).length + (pieces.length ? 1 : 0);
          if (bytes + next > 32768) break;
          bytes += next;
          pieces.push(text);
          if (elements.length < 100) {
            const ref = crypto.randomUUID();
            this.refs.set(ref, { element, node: walker.currentNode as Text, text,
              captureRequestId: request.captureRequestId, policies,
              root, box, coverage, scrollX, scrollY, url: location.href });
            elements.push({ ref, text: text.slice(0, 200) });
          }
        }
        metadata.dom = { status: "SUCCESS", text: pieces.join("\n"), elements, excludedCount };
      } catch {
        this.refs.clear();
        metadata.dom = { status: "FAILED", reason: "FILTER_FAILED" };
      }
    }
    return { metadata, images };
  }

  highlight(captureRequestId: string, ref: string, scrollIntoView = false): "HIGHLIGHTED" | "EVENT_ONLY" | "TARGET_STALE" {
    const entry = this.refs.get(ref);
    if (!entry || entry.captureRequestId !== captureRequestId || !entry.element.isConnected
      || !entry.node.isConnected || entry.node.parentElement !== entry.element
      || entry.node.textContent?.trim() !== entry.text
      || entry.url !== location.href || entry.root && !entry.root.contains(entry.element)
      || entry.coverage === "VIEWPORT" && (entry.scrollX !== scrollX || entry.scrollY !== scrollY)
      || !intersects(entry.element.getBoundingClientRect(), entry.box)
      || entry.policies.some(policy => !within(entry.element, policy.dom.allow, policy.dom.deny)
        || !within(entry.element, policy.captureScope.allow, policy.captureScope.deny))) return "TARGET_STALE";
    if (entry.policies.some(policy => policy.highlightMode !== "AUTO")) return "EVENT_ONLY";
    if (!(entry.element instanceof HTMLElement)) return "EVENT_ONLY";
    this.clearMark();
    const element = entry.element as HTMLElement;
    this.marked = { element, outline: element.style.outline, outlineOffset: element.style.outlineOffset };
    element.style.outline = "3px solid #f59e0b";
    element.style.outlineOffset = "3px";
    if (scrollIntoView && entry.policies.every(policy => policy.allowScroll === true))
      element.scrollIntoView({ behavior: "smooth", block: "center" });
    return "HIGHLIGHTED";
  }

  clear(): void { this.clearMark(); this.refs.clear(); }
  clearHighlight(): void { this.clearMark(); }
  private clearMark(): void {
    if (this.marked) {
      this.marked.element.style.outline = this.marked.outline;
      this.marked.element.style.outlineOffset = this.marked.outlineOffset;
      this.marked = undefined;
    }
  }
}
