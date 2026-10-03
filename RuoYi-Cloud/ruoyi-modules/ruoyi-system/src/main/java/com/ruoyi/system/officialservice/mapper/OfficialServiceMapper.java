package com.ruoyi.system.officialservice.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.officialservice.domain.OfficialSecret;
import com.ruoyi.system.officialservice.domain.OfficialService;
import com.ruoyi.system.officialservice.domain.OfficialIdempotency;

public interface OfficialServiceMapper
{
    String voiceBinding(@Param("voiceVersionId") long voiceVersionId);
    Long nextId();
    List<OfficialService> selectPage(@Param("capability") String capability, @Param("status") String status,
        @Param("offset") int offset, @Param("limit") int limit);
    int countPage(@Param("capability") String capability, @Param("status") String status);
    OfficialService selectById(@Param("serviceId") Long serviceId);
    OfficialService selectByIdForUpdate(@Param("serviceId") Long serviceId);
    OfficialService selectResolvable(@Param("serviceId") Long serviceId, @Param("expectedRevision") Long expectedRevision,
        @Param("capability") String capability);
    OfficialService selectDefaultResolvable(@Param("capability") String capability);
    OfficialSecret selectSecret(@Param("secretId") Long secretId);
    OfficialIdempotency selectIdempotencyForUpdate(@Param("accountId") Long accountId, @Param("scope") String scope, @Param("requestId") String requestId);
    int countGenerationBinding(@Param("serviceId") Long serviceId, @Param("revision") Long revision, @Param("taskId") Long taskId);
    int countVoiceBinding(@Param("serviceId") Long serviceId, @Param("revision") Long revision, @Param("voiceVersionId") Long voiceVersionId);
    int insert(OfficialService service);
    int update(OfficialService service);
    int updateStatus(@Param("serviceId") Long serviceId, @Param("status") String status, @Param("revision") Long revision);
    int insertSecret(@Param("secret") OfficialSecret secret, @Param("accountId") Long accountId, @Param("name") String name,
        @Param("suffix") String suffix);
    int insertIdempotency(@Param("id") Long id, @Param("accountId") Long accountId, @Param("scope") String scope,
        @Param("requestId") String requestId, @Param("requestHash") byte[] requestHash, @Param("resourceId") Long resourceId);
}
