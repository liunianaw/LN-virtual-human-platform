package com.ruoyi.session.runtime;

import com.ruoyi.session.runtime.RuntimeAuthorization.Grant;

public interface IChatRuntimeService
{
    Started start(Grant grant, long epoch, String requestId, String text, com.fasterxml.jackson.databind.JsonNode data);
    default Started start(Grant grant, long epoch, String requestId, String text)
    { return start(grant, epoch, requestId, text, null); }
    void launch(long turnId);
    boolean stop(RuntimePrincipal principal, long turnId, String reason);
    void cancelSession(long sessionId, long epoch);
    void cancelPrior(long sessionId, long epoch);
    record Started(long turnId, Long priorTurnId) { }
}
