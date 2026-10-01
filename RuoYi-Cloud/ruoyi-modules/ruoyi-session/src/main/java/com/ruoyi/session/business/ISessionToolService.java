package com.ruoyi.session.business;

import com.fasterxml.jackson.databind.JsonNode;

public interface ISessionToolService
{
    JsonNode invoke(String authorization, long sessionId, long skillId, String key,
        String externalUserId, JsonNode arguments);
}
