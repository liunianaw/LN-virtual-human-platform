package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.*;
import org.springframework.stereotype.Component;

@Component
public class VoiceExecutionClient
{
    private final ObjectMapper json;
    private final VoiceHttp http;
    public VoiceExecutionClient(ObjectMapper json) { this.json=json; this.http=new VoiceHttp(json); }
    private byte[] execution(String method,String path,Object body,String key,int limit)
    { return http.request(System.getenv("LN_VOICE_URL"),System.getenv("LN_VOICE_INTERNAL_BEARER"),method,"/internal/voice/v1/"+path,body,key,limit); }
    public void submit(VoiceProtocol.Request request) { execution("POST","attempts",request,request.attemptId(),65536); }
    public byte[] audio(long id,int limit) { return execution("GET","attempts/"+id+"/audio",null,null,limit); }
    public VoiceProtocol.Event status(long id) {
        try { var value=json.readTree(execution("GET","attempts/"+id,null,null,65536)).get("event");
            return value==null || value.isNull()?null:json.treeToValue(value,VoiceProtocol.Event.class); }
        catch(VoiceHttp.Failure error) { throw error; } catch(Exception error) { throw VoiceAttemptStore.problem("VOICE_OUTCOME_UNKNOWN"); }
    }
    public void cancel(long id) { execution("POST","attempts/"+id+"/cancel",null,null,65536); }
    public VoiceBinding binding(long id)
    {
        try { return json.readValue(http.request(System.getenv("LN_SESSION_TO_SYSTEM_URL"),System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER"),
            "GET","/internal/v1/voice-bindings/"+id,null,null,65536),VoiceBinding.class); }
        catch(Exception e) { throw VoiceAttemptStore.problem("VOICE_DISABLED"); }
    }
    public void verify(VoiceBinding binding)
    {
        VoiceBinding current=binding(Long.parseLong(binding.voiceVersionId()));
        if(!current.primaryOnly().equals(binding.primaryOnly())) throw VoiceAttemptStore.problem("VOICE_REQUEST_CONFLICT");
    }
}
