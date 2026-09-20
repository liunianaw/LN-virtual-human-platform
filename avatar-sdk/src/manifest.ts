import { AvatarSdkError } from "./errors.js";

/** The only action set accepted by formal LN Avatar packages. */
export const AVATAR_ACTION_CODES = [
  "idle",
  "speaking",
  "listening",
  "thinking",
  "nod",
  "shake_head",
  "wave",
  "happy",
] as const;

export type AvatarActionCode = (typeof AVATAR_ACTION_CODES)[number];

export interface AvatarAsset {
  fileId: string;
  sha256: string;
  url: string;
  expiresAt: string;
}

export interface AvatarFrame {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface AvatarAction {
  code: AvatarActionCode;
  frameCount: 6;
  fps: 6;
  loop: boolean;
  atlas: AvatarAsset;
  frames: readonly AvatarFrame[];
}

export interface AvatarManifest {
  packageType: "LN_AVATAR";
  schemaVersion: 1;
  framing: "FULL_BODY";
  lipSyncMode: "BASIC_SPEAKING";
  avatarId: string;
  versionId: string;
  width: 512;
  height: 768;
  anchor: Readonly<{ x: number; y: number }>;
  preview: AvatarAsset;
  baseImage: AvatarAsset;
  actions: readonly AvatarAction[];
}

const FORMAL_FRAME_LAYOUT: readonly AvatarFrame[] = [
  { x: 0, y: 0, width: 512, height: 768 },
  { x: 512, y: 0, width: 512, height: 768 },
  { x: 1024, y: 0, width: 512, height: 768 },
  { x: 0, y: 768, width: 512, height: 768 },
  { x: 512, y: 768, width: 512, height: 768 },
  { x: 1024, y: 768, width: 512, height: 768 },
];

const LOOPING_ACTIONS = new Set<AvatarActionCode>([
  "idle",
  "speaking",
  "listening",
  "thinking",
]);

function invalid(path: string, reason: string): never {
  throw new AvatarSdkError("MANIFEST_INVALID", `${path}: ${reason}`);
}

function objectAt(value: unknown, path: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid(path, "must be an object");
  }
  return value as Record<string, unknown>;
}

function exactKeys(value: Record<string, unknown>, keys: readonly string[], path: string): void {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) {
    invalid(path, `must contain exactly: ${expected.join(", ")}`);
  }
}

function nonEmptyString(value: unknown, path: string): string {
  if (typeof value !== "string" || value.trim().length === 0) invalid(path, "must be a non-empty string");
  return value;
}

function integer(value: unknown, path: string): number {
  if (typeof value !== "number" || !Number.isInteger(value)) invalid(path, "must be an integer");
  return value;
}

function parseAsset(value: unknown, path: string): AvatarAsset {
  const asset = objectAt(value, path);
  exactKeys(asset, ["fileId", "sha256", "url", "expiresAt"], path);
  const url = nonEmptyString(asset.url, `${path}.url`);
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" && parsed.protocol !== "http:") invalid(`${path}.url`, "must use http or https");
  } catch (error) {
    invalid(`${path}.url`, `must be an absolute URL (${String(error)})`);
  }
  const expiresAt = nonEmptyString(asset.expiresAt, `${path}.expiresAt`);
  if (Number.isNaN(Date.parse(expiresAt))) invalid(`${path}.expiresAt`, "must be an ISO-8601 timestamp");
  const sha256 = nonEmptyString(asset.sha256, `${path}.sha256`);
  if (!/^[a-f0-9]{64}$/i.test(sha256)) invalid(`${path}.sha256`, "must be a SHA-256 hex digest");
  return {
    fileId: nonEmptyString(asset.fileId, `${path}.fileId`),
    sha256: sha256.toLowerCase(),
    url,
    expiresAt,
  };
}

function parseFrame(value: unknown, path: string, index: number): AvatarFrame {
  const frame = objectAt(value, `${path}[${index}]`);
  exactKeys(frame, ["x", "y", "width", "height"], `${path}[${index}]`);
  const parsed = {
    x: integer(frame.x, `${path}[${index}].x`),
    y: integer(frame.y, `${path}[${index}].y`),
    width: integer(frame.width, `${path}[${index}].width`),
    height: integer(frame.height, `${path}[${index}].height`),
  };
  const expected = FORMAL_FRAME_LAYOUT[index];
  if (!expected || parsed.x !== expected.x || parsed.y !== expected.y || parsed.width !== expected.width || parsed.height !== expected.height) {
    invalid(`${path}[${index}]`, "does not match the formal 3x2 frame layout");
  }
  return parsed;
}

function parseAction(value: unknown, path: string): AvatarAction {
  const action = objectAt(value, path);
  exactKeys(action, ["code", "frameCount", "fps", "loop", "atlas", "frames"], path);
  const code = nonEmptyString(action.code, `${path}.code`);
  if (!AVATAR_ACTION_CODES.includes(code as AvatarActionCode)) invalid(`${path}.code`, "is not a supported action");
  if (action.frameCount !== 6) invalid(`${path}.frameCount`, "must be 6");
  if (action.fps !== 6) invalid(`${path}.fps`, "must be 6");
  if (typeof action.loop !== "boolean") invalid(`${path}.loop`, "must be a boolean");
  if (action.loop !== LOOPING_ACTIONS.has(code as AvatarActionCode)) invalid(`${path}.loop`, "does not match the formal action behavior");
  if (!Array.isArray(action.frames) || action.frames.length !== 6) invalid(`${path}.frames`, "must contain six frames");
  return {
    code: code as AvatarActionCode,
    frameCount: 6,
    fps: 6,
    loop: action.loop,
    atlas: parseAsset(action.atlas, `${path}.atlas`),
    frames: action.frames.map((frame, index) => parseFrame(frame, `${path}.frames`, index)),
  };
}

/**
 * Parses only the published `LN_AVATAR` v1 package shape.  Validation is
 * deliberately closed-world so a local preview manifest cannot be mistaken
 * for a runtime package.
 */
export function parseAvatarManifest(value: unknown): AvatarManifest {
  const manifest = objectAt(value, "manifest");
  exactKeys(manifest, [
    "packageType", "schemaVersion", "framing", "lipSyncMode", "avatarId", "versionId",
    "width", "height", "anchor", "preview", "baseImage", "actions",
  ], "manifest");
  if (manifest.packageType !== "LN_AVATAR") invalid("manifest.packageType", "must be LN_AVATAR");
  if (manifest.schemaVersion !== 1) invalid("manifest.schemaVersion", "must be 1");
  if (manifest.framing !== "FULL_BODY") invalid("manifest.framing", "must be FULL_BODY");
  if (manifest.lipSyncMode !== "BASIC_SPEAKING") invalid("manifest.lipSyncMode", "must be BASIC_SPEAKING");
  if (manifest.width !== 512 || manifest.height !== 768) invalid("manifest", "must use 512x768 frames");
  const anchor = objectAt(manifest.anchor, "manifest.anchor");
  exactKeys(anchor, ["x", "y"], "manifest.anchor");
  const parsedAnchor = { x: integer(anchor.x, "manifest.anchor.x"), y: integer(anchor.y, "manifest.anchor.y") };
  if (!Array.isArray(manifest.actions) || manifest.actions.length !== AVATAR_ACTION_CODES.length) {
    invalid("manifest.actions", "must contain each of the eight formal actions exactly once");
  }
  const actions = manifest.actions.map((action, index) => parseAction(action, `manifest.actions[${index}]`));
  const seen = new Set(actions.map((action) => action.code));
  if (seen.size !== AVATAR_ACTION_CODES.length || AVATAR_ACTION_CODES.some((code) => !seen.has(code))) {
    invalid("manifest.actions", "must contain each formal action exactly once");
  }
  return {
    packageType: "LN_AVATAR",
    schemaVersion: 1,
    framing: "FULL_BODY",
    lipSyncMode: "BASIC_SPEAKING",
    avatarId: nonEmptyString(manifest.avatarId, "manifest.avatarId"),
    versionId: nonEmptyString(manifest.versionId, "manifest.versionId"),
    width: 512,
    height: 768,
    anchor: parsedAnchor,
    preview: parseAsset(manifest.preview, "manifest.preview"),
    baseImage: parseAsset(manifest.baseImage, "manifest.baseImage"),
    actions,
  };
}

export function isAvatarActionCode(value: string): value is AvatarActionCode {
  return AVATAR_ACTION_CODES.includes(value as AvatarActionCode);
}
