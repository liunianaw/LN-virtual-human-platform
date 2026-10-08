package com.ruoyi.session.runtime;

import com.ruoyi.common.voice.VoiceProtocol;
import java.util.List;
import java.util.Map;

public interface IVoiceOrchestrationService
{
    void submit(RuntimeAuthorization.Grant grant,long epoch,TtsSynthesisWork work,TtsCompletionSink sink);
    byte[] audition(long administrator,long version,String key,String text);
    VoiceProtocol.Authorized authorize(long id,VoiceProtocol.Permit permit);
    void event(long id,VoiceProtocol.Event event);
    List<Map<String,Object>> diagnostics(Long account);
    Map<String,Object> pageDiagnostics(Long account, int pageNum, int pageSize, Long taskId, String status);
}
