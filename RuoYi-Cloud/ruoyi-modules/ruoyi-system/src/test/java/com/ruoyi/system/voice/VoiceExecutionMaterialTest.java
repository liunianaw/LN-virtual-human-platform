package com.ruoyi.system.voice;

import com.ruoyi.common.voice.*;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.officialservice.service.IOfficialServiceService;
import com.ruoyi.system.storage.ObjectStorage;
import com.ruoyi.system.voice.mapper.VoiceBindingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoiceExecutionMaterialTest
{
    @Test void materialIsVersionScopedAndReferenceMustMatchStorageAndDigest()
    {
        var binding=new VoiceBinding("5","COSYVOICE3","6",1,"model","revision","capability","reference:3","zh-CN",Map.of(),"3","参考",null,false,"1","http://model");
        var bindings=mock(VoiceBindingService.class);when(bindings.available(5,false)).thenReturn(binding);
        var mapper=mock(VoiceBindingMapper.class);var services=mock(IOfficialServiceService.class);
        when(services.resolve(6,1,"TTS",null,5L)).thenReturn(new IOfficialServiceService.ResolvedService("COSYVOICE3","http://model","model",Map.of(),"fixture-secret"));
        ObjectProvider<ObjectStorage> provider=mock(ObjectProvider.class);var storage=mock(ObjectStorage.class);
        when(provider.getIfAvailable()).thenReturn(storage);when(storage.provider()).thenReturn("COS");when(storage.bucket()).thenReturn("bucket");
        when(storage.readUrl("reference.wav")).thenReturn("https://trusted/ref?temporary=fixture");
        var file=new AssetFile();file.setId(3L);file.setStorageProvider("COS");file.setBucket("bucket");file.setObjectKey("reference.wav");file.setSizeBytes(48L);file.setSha256(new byte[32]);
        when(mapper.referenceFile(5)).thenReturn(file);
        var material=new VoiceExecutionMaterial(bindings,mapper,services,provider);
        var result=material.resolve(5);assertEquals("fixture-secret",result.credential());assertEquals(64,result.referenceAudioSha256().length());
        verify(services).resolve(6,1,"TTS",null,5L);verify(storage,times(1)).readUrl("reference.wav");
        file.setBucket("foreign-bucket");assertThrows(Exception.class,()->material.resolve(5));
        file.setBucket("bucket");file.setSha256(null);assertThrows(Exception.class,()->material.resolve(5));
        verify(storage,times(1)).readUrl("reference.wav");
    }
}
