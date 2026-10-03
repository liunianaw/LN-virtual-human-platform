package com.ruoyi.system.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.voice.mapper.VoiceBindingMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.voice.*;
import com.ruoyi.common.security.utils.SecurityUtils;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** Validates immutable voice semantics and protects every dependent resource. */
@Component
public class VoiceBindingService
{
    private final VoiceBindingMapper mapper;
    private final ObjectMapper json;
    private final VoiceCatalog catalog = new VoiceCatalog();
    public VoiceBindingService(VoiceBindingMapper mapper, ObjectMapper json) { this.mapper=mapper; this.json=json; }
    public VoiceCatalog catalog() { return catalog; }
    public java.util.List<java.util.Map<String,Object>> health(com.fasterxml.jackson.databind.JsonNode providers,boolean apiReachable) {
        return mapper.health().stream().map(s->{
            boolean valid=true;
            try { catalog.require(s.providerType(),s.modelId()).validateParameters(json.readValue(s.parameters(),new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){}));VoiceCatalog.validateEndpoint(s.endpoint(),s.providerType()); }
            catch(Exception error) { valid=false; }
            java.util.Map<String,Object> item=new LinkedHashMap<>();
            item.put("serviceId",s.serviceId());item.put("name",s.name());item.put("providerType",s.providerType());item.put("configValid",valid);
            item.put("apiReachable",apiReachable);item.put("modelReady",apiReachable && providers.path(s.providerType()).asBoolean());
            item.put("lastSynthesisStatus",s.lastSynthesisStatus()==null?"NOT_TESTED":s.lastSynthesisStatus());item.put("status",s.status());
            return item;
        }).toList();
    }
    public VoiceBinding read(long id)
    {
        try { return json.readValue(mapper.binding(id),VoiceBinding.class); }
        catch(Exception error) { throw new ServiceException("VOICE_BINDING_UNAVAILABLE",409); }
    }
    public VoiceBinding available(long id, boolean published)
    {
        if (id<=0 || mapper.available(id,published)!=1) throw new ServiceException("VOICE_DISABLED",409);
        VoiceBinding binding=read(id);
        try {
            catalog.require(binding.providerType(),binding.modelId()).validate(binding.providerVoiceRef(),binding.language(),binding.parameters(),binding.referenceAssetId()!=null);
            VoiceCatalog.validateEndpoint(binding.endpoint(),binding.providerType());
        } catch(IllegalArgumentException error) { throw new ServiceException("VOICE_CAPABILITY_UNSUPPORTED",409); }
        return binding;
    }
    public void save(long account, long id, VoiceServiceImpl.OfficialVoiceInput input, String provider, String model, long revision, String endpoint, java.util.Map<String,Object> serviceParameters)
    {
        if (!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可配置声音",403);
        try
        {
            VoiceCapability capability=catalog.require(provider,model);
            String language=input.language()==null?capability.languages().get(0):input.language();
            boolean reference=input.referenceAssetId()!=null;
            var combined=new LinkedHashMap<String,Object>(serviceParameters);
            combined.putAll(input.parameters());
            capability.validate(input.voiceAlias(),language,combined,reference);
            VoiceCatalog.validateEndpoint(endpoint,provider);
            if(reference && (mapper.referenceAvailable(input.referenceAssetId(),account)!=1 || input.referenceText()==null
                || input.referenceText().isBlank() || input.referenceText().length()>2000))
                throw new ServiceException("VOICE_REFERENCE_UNAVAILABLE",409);
            if(!reference && input.referenceText()!=null && !input.referenceText().isBlank())
                throw new ServiceException("VOICE_PARAMETER_INVALID",400);
            VoiceBinding fallback=null;
            if(input.fallbackVoiceVersionId()!=null)
            {
                fallback=available(input.fallbackVoiceVersionId(),true);
                if(!input.allowVoiceChange() || !catalog.require(fallback.providerType(),fallback.modelId()).fallbackTarget()
                    || !language.equals(fallback.language()) || fallback.fallback()!=null || fallback.voiceVersionId().equals(Long.toString(id)))
                    throw new ServiceException("VOICE_FALLBACK_INCOMPATIBLE",409);
                fallback=fallback.primaryOnly();
            }
            var parameters=new LinkedHashMap<String,Double>();
            combined.forEach((key,value)->parameters.put(key,((Number)value).doubleValue()));
            var binding=new VoiceBinding(Long.toString(id),capability.providerType(),Long.toString(input.officialServiceId()),revision,
                model,capability.modelRevision(),capability.capabilityVersion(),reference?"reference:"+input.referenceAssetId():input.voiceAlias(),language,parameters,
                reference?Long.toString(input.referenceAssetId()):null,input.referenceText(),fallback,input.allowVoiceChange(),"1",endpoint);
            if(mapper.save(id,binding.providerType(),json.writeValueAsString(binding),input.fallbackVoiceVersionId(),input.referenceAssetId())!=1)
                throw new ServiceException("VOICE_REQUEST_CONFLICT",409);
            if(fallback!=null) mapper.reference(account,id,"VOICE_VERSION",input.fallbackVoiceVersionId());
            if(reference) mapper.reference(account,id,"VOICE_REFERENCE",input.referenceAssetId());
        }
        catch(ServiceException error) { throw error; }
        catch(Exception error) { throw new ServiceException("VOICE_PARAMETER_INVALID",400); }
    }
    public java.util.List<VoiceBindingMapper.Reference> references(long account)
    { if(!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可配置声音",403); return mapper.references(account); }
}
