package com.ruoyi.common.voice;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class VoiceProtocol
{
    private VoiceProtocol() { }
    public static String hash(String value)
    { try { return hash(value.getBytes(StandardCharsets.UTF_8)); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static String hash(byte[] value)
    { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e) { throw new IllegalStateException(e); } }
    public record Request(int schemaVersion,String traceId,String taskId,String attemptId,long taskRevision,
        String requestHash,String voiceVersionId,String dispatchToken,String text,String textHash,int inputCharCount,
        Instant deadlineAt,long maxAudioBytes) { }
    public record Permit(long taskRevision,String workerInstanceId,String workerBootId,String dispatchToken) { }
    /** Transient send-time secrets; never persisted in binding, events or diagnostics. */
    public record Execution(String credential,String referenceAudioUrl,String referenceAudioSha256,Long referenceAudioBytes) { }
    public record Authorized(boolean authorized,VoiceBinding binding,Execution execution) {
        public Authorized(boolean authorized,VoiceBinding binding) { this(authorized,binding,null); }
    }
    public record Audio(String mimeType,String codec,int sampleRateHz,int channels,int sampleWidthBits,long byteLength,long durationMs,String sha256) { }
    public record Event(String eventId,String attemptId,long taskRevision,String requestHash,String workerInstanceId,
        String workerBootId,String state,String errorCode,String failureStage,Audio audio,String providerRequestId,
        String costSource,String modelRevision) { }
}
