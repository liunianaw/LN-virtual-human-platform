package com.ruoyi.system.voice.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Persistence for the existing official voice flow. No network work occurs here. */
public interface OfficialVoiceMapper {
    long nextId();
    List<ServiceRow> services();
    ServiceRow service(@Param("id") long id,@Param("locked") boolean locked);
    int count(@Param("published") boolean published,@Param("status") String status);
    List<SummaryRow> page(@Param("published") boolean published,@Param("status") String status,@Param("size") int size,@Param("offset") int offset);
    Head head(@Param("id") long id,@Param("account") Long account,@Param("locked") boolean locked);
    List<VersionRow> versions(@Param("voice") long voice);
    VersionRow version(@Param("voice") long voice,@Param("id") long id);
    Long latest(@Param("voice") long voice);
    int nextVersion(@Param("voice") long voice);
    void insertVoice(@Param("id") long id,@Param("account") long account,@Param("name") String name,@Param("description") String description);
    void insertVersion(@Param("id") long id,@Param("voice") long voice,@Param("account") long account,@Param("number") int number,
        @Param("service") long service,@Param("alias") String alias,@Param("language") String language,@Param("model") String model,@Param("parameters") String parameters,@Param("snapshot") String snapshot);
    int publish(@Param("voice") long voice,@Param("version") long version,@Param("revision") long revision);
    Idempotency idempotency(@Param("account") long account,@Param("scope") String scope,@Param("key") String key);
    void complete(@Param("id") long id,@Param("account") long account,@Param("scope") String scope,@Param("key") String key,@Param("hash") byte[] hash,@Param("resource") long resource);
    int claimAudition(@Param("account") long account,@Param("scope") String scope,@Param("key") String key,@Param("hash") byte[] hash,@Param("version") long version);
    void finishAudition(@Param("account") long account,@Param("scope") String scope,@Param("key") String key,@Param("status") String status);
    int completedAuditions(@Param("account") long account,@Param("scope") String scope,@Param("version") long version);
    record ServiceRow(long id,String name,String providerCode,String modelId,long revision,String endpoint,String parameters) { }
    record Head(long id,String name,String description,String status,Long currentVersionId,long revision,Instant updatedAt) { }
    record VersionRow(long id,int number,long serviceId,String alias,String language,String model,String parameters,String snapshot,Instant createdAt) { }
    record SummaryRow(long id,String name,String description,String status,Long currentVersionId,long revision,Instant createdAt,Integer number,String alias,String language,String model) { }
    record Idempotency(byte[] hash,long resourceId) { }
}
