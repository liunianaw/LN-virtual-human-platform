package com.ruoyi.system.voice;

import java.util.List;
import com.ruoyi.system.voice.VoiceServiceImpl.*;

public interface IOfficialVoiceService {
    List<OfficialServiceResponse> listAvailableOfficialServices();
    VoicePage listOfficial(Integer page,Integer size,String status,boolean publishedOnly);
    VoiceDetail getOfficial(long voiceId);
    VoiceMutation createOfficialCandidate(long account,OfficialVoiceInput input,String key);
    VoiceMutation createOfficialVersion(long account,long voice,OfficialVoiceInput input,String revision,String key);
    VoiceMutation publishOfficial(long account,long voice,long version,PublishRequest request,String revision,String key);
    byte[] audition(long account,long voice,long version,String text,String key);
}
