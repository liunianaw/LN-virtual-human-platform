package com.ruoyi.common.voice;

/** Safe execution cost evidence, distinct from billable logical speech usage. */
public record VoiceAttemptFact(String eventId,long accountId,long taskId,long attemptId,String purpose,
    Long applicationId,Long sessionId,Long turnId,String logicalOperationKey,long serviceId,String providerType,String modelId,
    String status,String providerRequestId,String costSource,String errorCode) { }
