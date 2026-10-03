package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.voice.*;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;
import com.ruoyi.system.storage.ObjectStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Service
public class VoiceManagementServiceImpl implements IVoiceManagementService
{
    private final VoiceBindingService bindings; private final ObjectMapper json; private final VoiceHttp http;
    private final AssetMapper files; private final IAssetStorageQuotaService quota; private final ObjectProvider<ObjectStorage> storage;
    private final TransactionTemplate tx;
    public VoiceManagementServiceImpl(VoiceBindingService bindings,ObjectMapper json,AssetMapper files,
        IAssetStorageQuotaService quota,ObjectProvider<ObjectStorage> storage,TransactionTemplate tx)
    { this.bindings=bindings;this.json=json;this.http=new VoiceHttp(json);this.files=files;this.quota=quota;this.storage=storage;this.tx=tx; }
    private void admin() { if(!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可管理声音",403); }
    public List<VoiceCapability> capabilities() { admin();return bindings.catalog().list(); }
    public List<com.ruoyi.system.voice.mapper.VoiceBindingMapper.Reference> references(long account) { admin();return bindings.references(account); }
    public Object diagnostics()
    {
        admin();try { return json.readTree(http.request(System.getenv("LN_SYSTEM_TO_SESSION_URL"),System.getenv("LN_SYSTEM_TO_SESSION_INTERNAL_BEARER"),"GET","/internal/v1/voice-attempts",null,null,262144)); }
        catch(Exception e) { throw new ServiceException("VOICE_DIAGNOSTICS_UNAVAILABLE",503); }
    }
    public Map<String,Object> readiness()
    {
        admin();try {
            var status=json.readTree(http.request(System.getenv("LN_VOICE_URL"),System.getenv("LN_VOICE_INTERNAL_BEARER"),"GET","/internal/voice/v1/readiness",null,null,65536));
            return Map.of("apiReachable",true,"modelReady",status.path("modelReady").asBoolean(),"providers",status.path("providers"),"services",bindings.health(status.path("endpoints"),true));
        } catch(Exception e) { return Map.of("apiReachable",false,"modelReady",false,"providers",Map.of(),"services",bindings.health(json.createObjectNode(),false)); }
    }
    public String upload(long account,MultipartFile input)
    {
        admin(); if(account<=0 || input==null || input.getSize()<44 || input.getSize()>5242880) throw new ServiceException("参考音频大小无效",400);
        ObjectStorage objects=storage.getIfAvailable();if(objects==null) throw new ServiceException("存储未配置",503);
        byte[] bytes;
        try(var stream=input.getInputStream()) {
            bytes=stream.readNBytes(5242881);
            VoiceWav.validate(bytes,5242880);
        } catch(Exception e) { throw new ServiceException("参考音频须为单声道 24 kHz PCM16 WAV",400); }
        AssetFile file=new AssetFile();file.setId(files.nextId());file.setAccountId(account);file.setPurpose("VOICE_SAMPLE");
        file.setStorageProvider(objects.provider());file.setBucket(objects.bucket());file.setObjectKey("voice-reference/"+account+"/"+file.getId()+".wav");
        file.setOriginalName("参考音频 "+file.getId());file.setContentType("audio/wav");file.setSizeBytes((long)bytes.length);
        file.setSha256(java.util.HexFormat.of().parseHex(VoiceProtocol.hash(bytes)));file.setStatus("UPLOADING");
        tx.executeWithoutResult(s->{file.setStorageReservationId(quota.reserve(account,file.getId(),file.getSizeBytes()));files.insertFile(file);});
        // A persisted UPLOADING file is reclaimed by the existing expiry worker if either step fails.
        objects.put(file.getObjectKey(),bytes,"audio/wav");
        tx.executeWithoutResult(s->quota.complete(account,file.getId()));
        return Long.toString(file.getId());
    }
}
