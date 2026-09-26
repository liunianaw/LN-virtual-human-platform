export type AvatarSdkErrorCode =
  | "MANIFEST_INVALID"
  | "ASSET_EXPIRED"
  | "ASSET_HASH_MISMATCH"
  | "ATLAS_LOAD_FAILED"
  | "ACTION_NOT_SUPPORTED"
  | "ACTION_QUEUE_FULL"
  | "AUDIO_PLAYBACK_BLOCKED"
  | "PLAYER_DESTROYED"
  | "PACKAGE_LOAD_CANCELLED";

/** A recoverable, documented failure from the browser-side SDK. */
export class AvatarSdkError extends Error {
  readonly name = "AvatarSdkError";

  constructor(
    readonly code: AvatarSdkErrorCode,
    message: string,
    readonly cause?: unknown,
  ) {
    super(message);
  }
}
