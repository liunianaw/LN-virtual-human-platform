package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.voice.*;
import com.ruoyi.system.voice.mapper.VoiceAttemptFactMapper;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceAttemptFactServiceImpl implements IVoiceAttemptFactService {
    private final VoiceAttemptFactMapper mapper; private final ObjectMapper json;
    public VoiceAttemptFactServiceImpl(VoiceAttemptFactMapper mapper,ObjectMapper json) { this.mapper=mapper;this.json=json; }
    @Transactional public void accept(VoiceAttemptFact f) {
        if(f==null || f.accountId()<=0 || f.taskId()<=0 || f.attemptId()<=0 || f.serviceId()<=0 || !token(f.eventId(),64)
            || f.purpose()==null || !Set.of("BUSINESS","AUDITION").contains(f.purpose()) || !token(f.providerType(),64) || !token(f.modelId(),128)
            || f.status()==null || !Set.of("SUCCEEDED","FAILED","UNKNOWN","CANCELLED").contains(f.status())
            || f.costSource()==null || !Set.of("UNKNOWN","PROVIDER","SELF_HOSTED","TEST").contains(f.costSource())
            || f.providerRequestId()!=null && f.providerRequestId().length()>128 || f.errorCode()!=null && !token(f.errorCode(),64)
            || "BUSINESS".equals(f.purpose()) && (f.applicationId()==null || f.applicationId()<=0 || f.sessionId()==null || f.sessionId()<=0 || f.turnId()==null || f.turnId()<=0 || !token(f.logicalOperationKey(),128))
            || "AUDITION".equals(f.purpose()) && (f.applicationId()!=null || f.sessionId()!=null || f.turnId()!=null || f.logicalOperationKey()!=null))
            throw new ServiceException("VOICE_PARAMETER_INVALID",400);
        if(mapper.account(f.accountId())==null) throw new ServiceException("调用归属账号不存在",404);
        String hash;
        try { hash=VoiceProtocol.hash(json.writeValueAsString(f)); } catch(Exception e) { throw new ServiceException("VOICE_PARAMETER_INVALID",400); }
        String prior=mapper.hash(f.eventId());
        if(prior!=null) { if(!prior.equalsIgnoreCase(hash)) throw new ServiceException("VOICE_REQUEST_CONFLICT",409);return; }
        String state=mapper.state(f.accountId(),f.attemptId());
        String identity=mapper.identity(f.attemptId());
        if(identity!=null && !identity.equals(f.accountId()+":"+f.taskId()+":"+f.serviceId()+":"+f.providerType()+":"+f.modelId()+":"+(f.applicationId()==null?0:f.applicationId())+":"+(f.sessionId()==null?0:f.sessionId())+":"+(f.turnId()==null?0:f.turnId())+":"+(f.logicalOperationKey()==null?"":f.logicalOperationKey()))) throw new ServiceException("VOICE_REQUEST_CONFLICT",409);
        if(state!=null && !state.equals(f.status()) && !"UNKNOWN".equals(state)) throw new ServiceException("VOICE_REQUEST_CONFLICT",409);
        mapper.save(f);mapper.inbox(f,hash);
        // Attempt evidence never increments p_usage_daily or user point consumption.
    }
    private static boolean token(String value,int max) { return value!=null && value.matches("[A-Za-z0-9:._/-]{1,"+max+"}"); }
}
