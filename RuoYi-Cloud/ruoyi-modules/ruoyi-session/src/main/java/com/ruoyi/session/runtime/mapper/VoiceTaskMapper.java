package com.ruoyi.session.runtime.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface VoiceTaskMapper
{
    long nextId();
    Long operation(@Param("account") long account,@Param("session") long session,@Param("turn") long turn,@Param("ordinal") int ordinal);
    void insertTask(@Param("t") Task task);
    void insertAttempt(@Param("a") Attempt attempt);
    Task task(@Param("id") long id);
    Task source(@Param("account") long account,@Param("purpose") String purpose,@Param("source") String source);
    Attempt attempt(@Param("id") long id);
    Attempt attemptForUpdate(@Param("id") long id);
    String settlement(@Param("task") long task);
    Attempt latest(@Param("task") long task);
    int authorize(@Param("id") long id,@Param("hash") String hash,@Param("revision") long revision,
        @Param("worker") String worker,@Param("boot") String boot);
    int dispatchOperation(@Param("task") long task);
    int running(@Param("id") long id);
    String eventHash(@Param("attempt") long attempt,@Param("event") String event);
    void event(@Param("attempt") long attempt,@Param("event") String event,@Param("hash") String hash);
    void fact(@Param("attempt") long attempt,@Param("event") String event);
    int finishAttempt(@Param("id") long id,@Param("state") String state,@Param("code") String code,
        @Param("stage") String stage,@Param("summary") String summary,@Param("providerRequest") String providerRequest,@Param("cost") String cost);
    int winner(@Param("task") long task,@Param("attempt") long attempt,@Param("result") String result);
    int finishTask(@Param("task") long task,@Param("state") String state,@Param("code") String code);
    int fallback(@Param("task") long task);
    int settle(@Param("task") long task,@Param("outcome") String outcome);
    int cancel(@Param("task") long task);
    int deliveryReview(@Param("task") long task);
    List<Attempt> queries();
    int claimQuery(@Param("id") long id);
    List<Task> recoverable(@Param("owner") String owner);
    List<Task> diagnostics(@Param("account") Long account);
    List<Task> pageDiagnostics(@Param("account") Long account, @Param("taskId") Long taskId,
        @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);
    long countDiagnostics(@Param("account") Long account, @Param("taskId") Long taskId, @Param("status") String status);
    List<Attempt> attempts(@Param("task") long task);
    record Task(long id,long accountId,String purpose,String sourceKey,String requestHash,long voiceVersionId,
        String bindingSnapshot,String policyVersion,String status,long revision,Long winnerAttemptId,Long sessionId,Long turnId,
        Long operationId,Long connectionEpoch,Long generation,Long grantId,String owner,Instant cancelRequestedAt,
        Instant deadlineAt,String inputHash,int inputCharCount,String errorCode,String resultReference) { }
    record Attempt(long id,long taskId,int attemptNo,String providerType,long serviceId,long voiceVersionId,
        String modelRevision,String capabilityVersion,String requestHash,String dispatchTokenHash,String state,
        String workerInstanceId,String workerBootId,long leaseEpoch,Instant leaseExpiresAt,
        String errorCode,String failureStage,String resultSummary,String providerRequestId,String costSource) { }
}
