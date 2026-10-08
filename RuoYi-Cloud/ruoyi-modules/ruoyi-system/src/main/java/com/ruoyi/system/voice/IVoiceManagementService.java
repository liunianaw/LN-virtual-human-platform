package com.ruoyi.system.voice;

import com.ruoyi.common.voice.VoiceCapability;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public interface IVoiceManagementService
{
    List<VoiceCapability> capabilities();
    List<com.ruoyi.system.voice.mapper.VoiceBindingMapper.Reference> references(long account);
    String upload(long account,MultipartFile file);
    Object diagnostics();
    Object pageDiagnostics(int pageNum,int pageSize,Long taskId,String status);
    Map<String,Object> readiness();
}
