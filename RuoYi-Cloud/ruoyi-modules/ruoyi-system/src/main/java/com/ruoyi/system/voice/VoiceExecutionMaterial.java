package com.ruoyi.system.voice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.voice.VoiceProtocol;
import com.ruoyi.system.officialservice.service.IOfficialServiceService;
import com.ruoyi.system.storage.ObjectStorage;
import com.ruoyi.system.voice.mapper.VoiceBindingMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Binding-scoped send-time resolution; no secrets or signed links enter persistent snapshots. */
@Component
public class VoiceExecutionMaterial
{
    private final VoiceBindingService bindings;
    private final VoiceBindingMapper mapper;
    private final IOfficialServiceService services;
    private final ObjectProvider<ObjectStorage> storage;
    public VoiceExecutionMaterial(VoiceBindingService bindings,VoiceBindingMapper mapper,
        IOfficialServiceService services,ObjectProvider<ObjectStorage> storage)
    { this.bindings=bindings;this.mapper=mapper;this.services=services;this.storage=storage; }

    public VoiceProtocol.Execution resolve(long version)
    {
        var binding=bindings.available(version,false);
        var config=services.resolve(Long.parseLong(binding.serviceId()),binding.serviceRevision(),"TTS",null,version);
        if(!binding.providerType().equals(config.providerCode()) || !binding.endpoint().equals(config.endpoint())
            || !binding.modelId().equals(config.modelId())) throw new ServiceException("VOICE_REQUEST_CONFLICT",409);
        if(binding.referenceAssetId()==null) return new VoiceProtocol.Execution(config.credential(),null,null,null);
        var file=mapper.referenceFile(version);
        ObjectStorage objects=storage.getIfAvailable();
        if(file==null || objects==null || !binding.referenceAssetId().equals(Long.toString(file.getId()))
            || !objects.provider().equals(file.getStorageProvider()) || !objects.bucket().equals(file.getBucket())
            || file.getSha256()==null || file.getSha256().length!=32 || file.getSizeBytes()==null
            || file.getSizeBytes()<44 || file.getSizeBytes()>5242880)
            throw new ServiceException("VOICE_REFERENCE_UNAVAILABLE",409);
        return new VoiceProtocol.Execution(config.credential(),objects.readUrl(file.getObjectKey()),
            java.util.HexFormat.of().formatHex(file.getSha256()),file.getSizeBytes());
    }
}
