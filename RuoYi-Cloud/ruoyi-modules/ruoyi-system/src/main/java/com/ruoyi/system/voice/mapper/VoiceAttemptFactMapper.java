package com.ruoyi.system.voice.mapper;

import com.ruoyi.common.voice.VoiceAttemptFact;
import org.apache.ibatis.annotations.Param;

public interface VoiceAttemptFactMapper {
    Long account(@Param("id") long id);
    String hash(@Param("event") String event);
    String state(@Param("account") long account,@Param("attempt") long attempt);
    String identity(@Param("attempt") long attempt);
    void inbox(@Param("f") VoiceAttemptFact fact,@Param("hash") String hash);
    void save(@Param("f") VoiceAttemptFact fact);
}
