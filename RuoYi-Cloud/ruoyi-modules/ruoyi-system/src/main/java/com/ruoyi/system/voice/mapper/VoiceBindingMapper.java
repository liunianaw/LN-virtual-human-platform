package com.ruoyi.system.voice.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface VoiceBindingMapper
{
    String binding(@Param("id") long id);
    com.ruoyi.system.asset.domain.AssetFile referenceFile(@Param("version") long version);
    List<ServiceHealth> health();
    int available(@Param("id") long id, @Param("published") boolean published);
    int save(@Param("id") long id, @Param("provider") String provider, @Param("binding") String binding,
        @Param("fallback") Long fallback, @Param("reference") Long reference);
    int referenceAvailable(@Param("id") long id, @Param("account") long account);
    List<Reference> references(@Param("account") long account);
    void reference(@Param("account") long account, @Param("holder") long holder,
        @Param("type") String type, @Param("resource") long resource);
    record Reference(String id, String name) { }
    record ServiceHealth(String serviceId,String name,String providerType,String modelId,String status,String endpoint,String parameters,String lastSynthesisStatus) { }
}
